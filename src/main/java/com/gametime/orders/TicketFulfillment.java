package com.gametime.orders;

import java.util.UUID;

/** Separate dependency for the post-payment completion step. */
public interface TicketFulfillment {
    void complete(UUID orderId) throws CompletionException;
}
