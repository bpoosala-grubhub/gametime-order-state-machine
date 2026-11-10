package com.gametime.orders;

import java.util.List;
import java.util.UUID;

/** Read-only copy returned to API callers; history cannot be mutated by a caller. */
public record OrderSnapshot(UUID id, OrderState state, List<StateChange> history) {
    public OrderSnapshot {
        history = List.copyOf(history);
    }
}
