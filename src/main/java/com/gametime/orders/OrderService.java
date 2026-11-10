package com.gametime.orders;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Coordinates checkout and compensates for a failed completion when payment was authorized. */
public final class OrderService {
    private final ConcurrentMap<UUID, Order> orders = new ConcurrentHashMap<>();
    private final PaymentGateway payment;
    private final TicketFulfillment fulfillment;
    private final Clock clock;

    public OrderService(PaymentGateway payment, TicketFulfillment fulfillment, Clock clock) {
        this.payment = payment;
        this.fulfillment = fulfillment;
        this.clock = clock;
    }

    public OrderSnapshot create() {
        return create(UUID.randomUUID());
    }

    OrderSnapshot create(UUID id) {
        Order order = new Order(id, clock.instant());
        orders.put(id, order);
        return order.snapshot();
    }

    public OrderSnapshot get(UUID id) {
        Order order = find(id);
        synchronized (order) {
            return order.snapshot();
        }
    }

    public OrderSnapshot authorize(UUID id) {
        Order order = find(id);
        synchronized (order) { // Prevent two concurrent requests from authorizing the same order.
            require(order, OrderState.INITIALIZED, "authorize payment");
            try {
                payment.authorize(id);
                order.transitionTo(OrderState.PAYMENT_AUTHORIZED, clock.instant(), "Payment authorized");
            } catch (PaymentDeclinedException declined) {
                // No authorization occurred, so there is nothing to void.
                order.transitionTo(OrderState.REJECTED, clock.instant(),
                        "Payment declined: " + declined.getMessage());
            }
            return order.snapshot();
        }
    }

    public OrderSnapshot complete(UUID id) {
        Order order = find(id);
        synchronized (order) {
            require(order, OrderState.PAYMENT_AUTHORIZED, "complete order");
            try {
                fulfillment.complete(id);
                order.transitionTo(OrderState.COMPLETE, clock.instant(), "Ticket completion succeeded");
            } catch (CompletionException completionFailure) {
                // The payment was authorized. Undo that authorization before calling this cancelled.
                try {
                    payment.voidAuthorization(id);
                    order.transitionTo(OrderState.CANCELLED, clock.instant(),
                            "Completion failed: " + completionFailure.getMessage()
                                    + "; payment authorization voided");
                } catch (PaymentVoidException voidFailure) {
                    // We cannot claim the payment is safe: record both causes for human follow-up.
                    order.transitionTo(OrderState.NEEDS_ATTENTION, clock.instant(),
                            "Completion failed: " + completionFailure.getMessage()
                                    + "; payment void failed: " + voidFailure.getMessage());
                    throw new PartialFailureException(order.snapshot(), completionFailure, voidFailure);
                }
            }
            return order.snapshot();
        }
    }

    private Order find(UUID id) {
        Order order = orders.get(id);
        if (order == null) throw new OrderNotFoundException("Order " + id + " was not found");
        return order;
    }

    private void require(Order order, OrderState state, String action) {
        if (order.state() != state) {
            throw new InvalidTransitionException(
                    "Cannot " + action + " while order is " + order.state().apiValue());
        }
    }
}
