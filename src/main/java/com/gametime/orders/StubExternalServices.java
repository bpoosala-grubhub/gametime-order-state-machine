package com.gametime.orders;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Simulates external integrations; the order/domain logic contains no scenario checks. */
public final class StubExternalServices implements PaymentGateway, TicketFulfillment {
    private final ConcurrentMap<UUID, DemoScenario> scenarios = new ConcurrentHashMap<>();

    public void setScenario(UUID id, DemoScenario scenario) {
        scenarios.put(id, scenario);
    }

    private DemoScenario scenarioFor(UUID id) {
        return scenarios.getOrDefault(id, DemoScenario.HAPPY_PATH);
    }

    @Override
    public void authorize(UUID orderId) throws PaymentDeclinedException {
        if (scenarioFor(orderId) == DemoScenario.PAYMENT_DECLINED) {
            throw new PaymentDeclinedException("Card was declined by payment provider");
        }
    }

    @Override
    public void complete(UUID orderId) throws CompletionException {
        if (scenarioFor(orderId) == DemoScenario.COMPLETION_FAILURE_VOID_SUCCESS
                || scenarioFor(orderId) == DemoScenario.COMPLETION_FAILURE_VOID_FAILED) {
            throw new CompletionException("Ticket completion service unavailable");
        }
    }

    @Override
    public void voidAuthorization(UUID orderId) throws PaymentVoidException {
        if (scenarioFor(orderId) == DemoScenario.COMPLETION_FAILURE_VOID_FAILED) {
            throw new PaymentVoidException("Payment provider could not void authorization");
        }
    }
}
