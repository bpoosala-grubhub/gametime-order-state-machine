package com.gametime.orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owns the valid-transition rules; callers cannot set the state directly. */
final class Order {
    private final UUID id;
    private OrderState state = OrderState.INITIALIZED;
    private final List<StateChange> history = new ArrayList<>();

    Order(UUID id, Instant createdAt) {
        this.id = id;
        history.add(new StateChange(null, OrderState.INITIALIZED, createdAt, "Order created"));
    }

    OrderState state() {
        return state;
    }

    void transitionTo(OrderState next, Instant at, String reason) {
        // A state change is never legal just because the caller asks for it.
        Set<OrderState> allowed = switch (state) {
            case INITIALIZED -> Set.of(OrderState.PAYMENT_AUTHORIZED, OrderState.REJECTED);
            case PAYMENT_AUTHORIZED -> Set.of(
                    OrderState.COMPLETE, OrderState.CANCELLED, OrderState.NEEDS_ATTENTION);
            case COMPLETE, REJECTED, CANCELLED, NEEDS_ATTENTION -> Set.of();
        };
        if (!allowed.contains(next)) {
            throw new InvalidTransitionException("Cannot move from " + state.apiValue()
                    + " to " + next.apiValue());
        }
        OrderState previous = state;
        state = next;
        history.add(new StateChange(previous, next, at, reason));
    }

    OrderSnapshot snapshot() {
        return new OrderSnapshot(id, state, List.copyOf(history));
    }
}
