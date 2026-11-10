package com.gametime.orders;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Minimal, dependency-free HTTP adapter around the order state machine. */
public final class OrderHttpServer {
    private static final Logger LOG = Logger.getLogger(OrderHttpServer.class.getName());

    private OrderHttpServer() { }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = create(port);
        server.start();
        System.out.println("Order API running at http://localhost:" + server.getAddress().getPort());
    }

    /** Also used by the HTTP integration tests; port 0 asks the OS for an available port. */
    public static HttpServer create(int port) throws IOException {
        StubExternalServices stubs = new StubExternalServices();
        OrderService service = new OrderService(stubs, stubs, Clock.systemUTC());
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/orders", exchange -> handle(exchange, service, stubs));
        return server;
    }

    private static void handle(HttpExchange exchange, OrderService service, StubExternalServices stubs)
            throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String[] segments = path.split("/");

            if (segments.length == 2 && path.equals("/orders") && method.equals("POST")) {
                if (exchange.getRequestBody().readAllBytes().length != 0) {
                    send(exchange, 400, error("invalid_request", "No request body expected; use ?scenario=..."));
                    return;
                }
                DemoScenario scenario = scenarioFromQuery(exchange.getRequestURI().getRawQuery());
                UUID id = UUID.randomUUID();
                stubs.setScenario(id, scenario);
                OrderSnapshot created = service.create(id);
                exchange.getResponseHeaders().set("Location", "/orders/" + created.id());
                send(exchange, 201, orderJson(created));
                return;
            }

            if (segments.length == 3 && segments[1].equals("orders") && method.equals("GET")) {
                UUID id = parseId(segments[2]);
                send(exchange, 200, orderJson(service.get(id)));
                return;
            }

            if (segments.length == 4 && segments[1].equals("orders") && method.equals("POST")) {
                UUID id = parseId(segments[2]);
                if (segments[3].equals("authorize")) {
                    OrderSnapshot result = service.authorize(id);
                    if (result.state() == OrderState.REJECTED) {
                        send(exchange, 422, failedOrder("payment_declined", "Payment declined", result));
                    } else {
                        send(exchange, 200, orderJson(result));
                    }
                    return;
                }
                if (segments[3].equals("complete")) {
                    OrderSnapshot result = service.complete(id);
                    if (result.state() == OrderState.CANCELLED) {
                        send(exchange, 422, failedOrder("completion_failed_payment_voided",
                                "Completion failed; payment authorization was successfully voided", result));
                    } else {
                        send(exchange, 200, orderJson(result));
                    }
                    return;
                }
            }
            send(exchange, 404, error("not_found", "Endpoint not found"));
        } catch (IllegalArgumentException invalidInput) {
            send(exchange, 400, error("invalid_request", invalidInput.getMessage()));
        } catch (OrderNotFoundException missing) {
            send(exchange, 404, error("order_not_found", missing.getMessage()));
        } catch (InvalidTransitionException conflict) {
            send(exchange, 409, error("invalid_transition", conflict.getMessage()));
        } catch (PartialFailureException partial) {
            // Both the HTTP caller and logs can identify the order requiring manual recovery.
            LOG.log(Level.SEVERE, "Order requires manual resolution: " + partial.order().id(), partial);
            send(exchange, 502, failedOrder("payment_void_failed", partial.getMessage(), partial.order()));
        } catch (Exception unexpected) {
            LOG.log(Level.SEVERE, "Unexpected checkout failure", unexpected);
            send(exchange, 500, error("internal_error", "Unexpected failure; inspect server logs"));
        } finally {
            exchange.close();
        }
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid order ID: " + id);
        }
    }

    private static DemoScenario scenarioFromQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return DemoScenario.HAPPY_PATH;
        String[] parts = rawQuery.split("&", -1);
        if (parts.length != 1 || !parts[0].startsWith("scenario=")) {
            throw new IllegalArgumentException("Only one scenario query parameter is accepted");
        }
        String value = URLDecoder.decode(parts[0].substring("scenario=".length()), StandardCharsets.UTF_8);
        try {
            return DemoScenario.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown demo scenario: " + value);
        }
    }

    private static String orderJson(OrderSnapshot order) {
        StringBuilder json = new StringBuilder("{\"id\":")
                .append(quoted(order.id().toString()))
                .append(",\"state\":").append(quoted(order.state().apiValue()))
                .append(",\"history\":[");
        for (int i = 0; i < order.history().size(); i++) {
            StateChange event = order.history().get(i);
            if (i > 0) json.append(',');
            json.append("{\"from\":").append(event.from() == null ? "null" : quoted(event.from().apiValue()))
                    .append(",\"to\":").append(quoted(event.to().apiValue()))
                    .append(",\"at\":").append(quoted(event.at().toString()))
                    .append(",\"reason\":").append(quoted(event.reason()))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static String failedOrder(String code, String message, OrderSnapshot order) {
        return "{\"error\":" + quoted(code) + ",\"message\":" + quoted(message)
                + ",\"order\":" + orderJson(order) + "}";
    }

    private static String error(String code, String message) {
        return "{\"error\":" + quoted(code) + ",\"message\":" + quoted(message) + "}";
    }

    /** Escapes external error descriptions safely without adding a JSON dependency. */
    private static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
