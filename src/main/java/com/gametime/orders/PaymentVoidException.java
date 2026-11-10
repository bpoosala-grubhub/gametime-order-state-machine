package com.gametime.orders;

/** A previously authorized payment could not be voided. */
public class PaymentVoidException extends Exception {
    public PaymentVoidException(String message) { super(message); }
}
