package com.gametime.orders;

import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runnable assertion-based tests using only the JDK (no downloading test libraries). */
public final class OrderTests {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        run("happy path", OrderTests::happyPath);
        run("payment declined, no void", OrderTests::paymentDecline);
        run("completion failure, void succeeds", OrderTests::completionFailedAndVoided);
        run("completion failure, void fails, attention surfaced", OrderTests::completionAndVoidFailed);
        run("invalid and terminal transitions are blocked", OrderTests::invalidTransitions);
        run("HTTP API covers all recovery paths and audit trail", OrderTests::httpIntegration);
        System.out.println("PASS: " + passed + " tests");
    }

    private static void happyPath() {
        TestDependencies stub = new TestDependencies();
        OrderService service = service(stub);
        OrderSnapshot initial = service.create();
        check(initial.state() == OrderState.INITIALIZED, "starts initialized");
        check(initial.history().size() == 1, "creation is in history");

        OrderSnapshot authorized = service.authorize(initial.id());
        check(authorized.state() == OrderState.PAYMENT_AUTHORIZED, "authorization succeeded");
        OrderSnapshot complete = service.complete(initial.id());
        check(complete.state() == OrderState.COMPLETE, "order completed");
        check(complete.history().size() == 3, "three timestamped states");
        check(complete.history().get(0).at() != null, "initial timestamp exists");
        check(complete.history().get(1).from() == OrderState.INITIALIZED, "authorization from initialized");
        check(complete.history().get(2).from() == OrderState.PAYMENT_AUTHORIZED, "completion from authorized");
        check(stub.authorizeCount == 1 && stub.completeCount == 1 && stub.voidCount == 0,
                "only expected dependencies called");
        check(service.get(initial.id()).state() == OrderState.COMPLETE, "GET shows completed state");
    }

    private static void paymentDecline() {
        TestDependencies stub = new TestDependencies();
        stub.decline = true;
        OrderService service = service(stub);
        OrderSnapshot rejected = service.authorize(service.create().id());
        check(rejected.state() == OrderState.REJECTED, "decline rejects order");
        check(rejected.history().size() == 2, "rejection is recorded");
        check(rejected.history().get(1).reason().contains("declined"), "decline reason recorded");
        check(stub.voidCount == 0, "must not void on decline");
        check(stub.completeCount == 0, "must not complete after decline");
    }

    private static void completionFailedAndVoided() {
        TestDependencies stub = new TestDependencies();
        stub.completionFails = true;
        OrderService service = service(stub);
        UUID id = service.create().id();
        service.authorize(id);
        OrderSnapshot cancelled = service.complete(id);
        check(cancelled.state() == OrderState.CANCELLED, "cancel only after successful void");
        check(stub.voidCount == 1, "void was attempted once");
        check(cancelled.history().size() == 3, "cancelled state captured");
        check(cancelled.history().get(2).reason().contains("voided"), "recovery recorded");
    }

    private static void completionAndVoidFailed() {
        TestDependencies stub = new TestDependencies();
        stub.completionFails = true;
        stub.voidFails = true;
        OrderService service = service(stub);
        UUID id = service.create().id();
        service.authorize(id);
        try {
            service.complete(id);
            throw new AssertionError("Partial failure must be thrown to the caller");
        } catch (PartialFailureException failure) {
            check(failure.order().state() == OrderState.NEEDS_ATTENTION, "attention state is returned");
            check(failure.getMessage().contains("Completion failed"), "completion error surfaced");
            check(failure.getMessage().contains("payment void failed"), "void error surfaced");
        }
        check(service.get(id).state() == OrderState.NEEDS_ATTENTION, "attention state persists in store");
        check(service.get(id).history().size() == 3, "attention transition is recorded");
        check(stub.voidCount == 1, "void attempted exactly once");
    }

    private static void invalidTransitions() {
        TestDependencies initialStub = new TestDependencies();
        OrderService initialService = service(initialStub);
        UUID initialId = initialService.create().id();
        expectInvalidTransition(() -> initialService.complete(initialId));
        check(initialService.get(initialId).history().size() == 1,
                "completion before authorization doesn't change history");
        check(initialStub.completeCount == 0, "completion before authorization doesn't call fulfillment");

        for (OrderState terminalState : new OrderState[] {
                OrderState.COMPLETE, OrderState.REJECTED, OrderState.CANCELLED, OrderState.NEEDS_ATTENTION
        }) {
            TestDependencies stub = new TestDependencies();
            OrderService service = service(stub);
            UUID id = service.create().id();
            switch (terminalState) {
                case COMPLETE -> {
                    service.authorize(id);
                    service.complete(id);
                }
                case REJECTED -> {
                    stub.decline = true;
                    service.authorize(id);
                }
                case CANCELLED -> {
                    stub.completionFails = true;
                    service.authorize(id);
                    service.complete(id);
                }
                case NEEDS_ATTENTION -> {
                    stub.completionFails = true;
                    stub.voidFails = true;
                    service.authorize(id);
                    try {
                        service.complete(id);
                        throw new AssertionError("Failed void must leave order needing attention");
                    } catch (PartialFailureException expected) {
                        // The partial failure is the expected path into this terminal state.
                    }
                }
                default -> throw new AssertionError("Not a terminal state: " + terminalState);
            }

            OrderSnapshot before = service.get(id);
            int authorizeCalls = stub.authorizeCount;
            int completeCalls = stub.completeCount;
            int voidCalls = stub.voidCount;
            expectInvalidTransition(() -> service.authorize(id));
            expectInvalidTransition(() -> service.complete(id));
            OrderSnapshot after = service.get(id);
            check(after.state() == terminalState, terminalState + " remains terminal");
            check(after.history().equals(before.history()), terminalState + " history is unchanged");
            check(stub.authorizeCount == authorizeCalls && stub.completeCount == completeCalls
                    && stub.voidCount == voidCalls, terminalState + " makes no downstream calls");
        }
    }

    private static void expectInvalidTransition(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected an invalid transition");
        } catch (InvalidTransitionException expected) {
            // Expected: terminal and out-of-order actions are rejected.
        }
    }

    private static void httpIntegration() throws Exception {
        HttpServer server = OrderHttpServer.create(0);
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String base = "http://localhost:" + server.getAddress().getPort();
            HttpResponse<String> declineCreate = post(client, base + "/orders?scenario=PAYMENT_DECLINED");
            Matcher declineMatcher = Pattern.compile("\\\"id\\\":\\\"([a-f0-9-]+)\\\"")
                .matcher(declineCreate.body());
            check(declineCreate.statusCode() == 201 && declineMatcher.find(), "HTTP creates decline scenario");
            String declineId = declineMatcher.group(1);
            HttpResponse<String> decline = post(client, base + "/orders/" + declineId + "/authorize");
            check(decline.statusCode() == 422 && decline.body().contains("\"error\":\"payment_declined\"")
                && decline.body().contains("\"state\":\"rejected\""), "HTTP surfaces payment decline");
            HttpResponse<String> declinedOrder = get(client, base + "/orders/" + declineId);
            check(declinedOrder.statusCode() == 200 && declinedOrder.body().contains("\"state\":\"rejected\"")
                && declinedOrder.body().contains("\"history\":"), "GET returns declined order history");
                check(post(client, base + "/orders/" + declineId + "/authorize").statusCode() == 409
                    && post(client, base + "/orders/" + declineId + "/complete").statusCode() == 409,
                    "HTTP rejects actions on a rejected order");
                check(get(client, base + "/orders/" + declineId).body().equals(declinedOrder.body()),
                    "rejected order history is unchanged after invalid actions");

            HttpResponse<String> voidCreate = post(client, base + "/orders?scenario=COMPLETION_FAILURE_VOID_SUCCESS");
            Matcher voidMatcher = Pattern.compile("\\\"id\\\":\\\"([a-f0-9-]+)\\\"")
                .matcher(voidCreate.body());
            check(voidCreate.statusCode() == 201 && voidMatcher.find(), "HTTP creates void-success scenario");
            String voidId = voidMatcher.group(1);
            check(post(client, base + "/orders/" + voidId + "/authorize").statusCode() == 200,
                "HTTP authorizes payment before completion failure");
            HttpResponse<String> voided = post(client, base + "/orders/" + voidId + "/complete");
            check(voided.statusCode() == 422
                && voided.body().contains("\"error\":\"completion_failed_payment_voided\"")
                && voided.body().contains("\"state\":\"cancelled\""), "HTTP surfaces completion failure after void");
            HttpResponse<String> voidedOrder = get(client, base + "/orders/" + voidId);
            check(voidedOrder.statusCode() == 200 && voidedOrder.body().contains("\"state\":\"cancelled\"")
                && voidedOrder.body().contains("\"history\":"), "GET returns cancelled order history");
                check(post(client, base + "/orders/" + voidId + "/authorize").statusCode() == 409
                    && post(client, base + "/orders/" + voidId + "/complete").statusCode() == 409,
                    "HTTP rejects actions on a cancelled order");
                check(get(client, base + "/orders/" + voidId).body().equals(voidedOrder.body()),
                    "cancelled order history is unchanged after invalid actions");

            HttpResponse<String> create = post(client, base + "/orders?scenario=COMPLETION_FAILURE_VOID_FAILED");
            check(create.statusCode() == 201, "HTTP creates an order");
            Matcher matcher = Pattern.compile("\\\"id\\\":\\\"([a-f0-9-]+)\\\"").matcher(create.body());
            check(matcher.find(), "creation response contains UUID");
            String id = matcher.group(1);
            check(post(client, base + "/orders/" + id + "/authorize").statusCode() == 200,
                    "HTTP authorizes payment");
            HttpResponse<String> completion = post(client, base + "/orders/" + id + "/complete");
            check(completion.statusCode() == 502, "failed void is surfaced as upstream failure");
            check(completion.body().contains("\"error\":\"payment_void_failed\""), "failure code is explicit");
            check(completion.body().contains("\"state\":\"needs_attention\""), "failure state is explicit");
            HttpResponse<String> lookup = get(client, base + "/orders/" + id);
            check(lookup.statusCode() == 200 && lookup.body().contains("\"history\""),
                    "GET returns persisted order history");
                check(post(client, base + "/orders/" + id + "/authorize").statusCode() == 409
                    && post(client, base + "/orders/" + id + "/complete").statusCode() == 409,
                    "HTTP rejects actions on an order needing attention");
                check(get(client, base + "/orders/" + id).body().equals(lookup.body()),
                    "needs-attention order history is unchanged after invalid actions");
            check(post(client, base + "/orders?scenario=not_a_scenario").statusCode() == 400,
                    "HTTP validates scenario");
            check(get(client, base + "/orders/" + UUID.randomUUID()).statusCode() == 404,
                    "missing orders respond 404");
        } finally {
            server.stop(0);
        }
    }

    private static OrderService service(TestDependencies stub) {
        return new OrderService(stub, stub, Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC));
    }

    private static HttpResponse<String> post(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void run(String name, TestCase test) throws Exception {
        test.execute();
        passed++;
        System.out.println("PASS: " + name);
    }

    @FunctionalInterface
    interface TestCase { void execute() throws Exception; }

    /** Unit tests drive the ports directly; no HTTP or scenario registry in the domain tests. */
    private static final class TestDependencies implements PaymentGateway, TicketFulfillment {
        boolean decline;
        boolean completionFails;
        boolean voidFails;
        int authorizeCount;
        int voidCount;
        int completeCount;

        @Override
        public void authorize(UUID orderId) throws PaymentDeclinedException {
            authorizeCount++;
            if (decline) throw new PaymentDeclinedException("declined");
        }

        @Override
        public void voidAuthorization(UUID orderId) throws PaymentVoidException {
            voidCount++;
            if (voidFails) throw new PaymentVoidException("provider void rejected");
        }

        @Override
        public void complete(UUID orderId) throws CompletionException {
            completeCount++;
            if (completionFails) throw new CompletionException("fulfillment down");
        }
    }
}
