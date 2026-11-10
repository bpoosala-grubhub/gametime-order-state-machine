package com.gametime.orders;

/** A payment was declined before authorization. */
public class PaymentDeclinedException extends Exception {
    public PaymentDeclinedException(String message) { super(message); }
}
