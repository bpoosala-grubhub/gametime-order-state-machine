package com.gametime.orders;

class InvalidTransitionException extends RuntimeException {
    InvalidTransitionException(String message) { super(message); }
}

class OrderNotFoundException extends RuntimeException {
    OrderNotFoundException(String message) { super(message); }
}

/** Carries the persisted attention state and both failure causes to the API boundary. */
class PartialFailureException extends RuntimeException {
    private final OrderSnapshot order;

    PartialFailureException(OrderSnapshot order, CompletionException completion, PaymentVoidException voidError) {
        super("Completion failed: " + completion.getMessage()
                + "; payment void failed: " + voidError.getMessage(), voidError);
        this.order = order;
        addSuppressed(completion);
    }

    OrderSnapshot order() { return order; }
}