package com.gametime.orders;

/** The post-payment completion step failed. */
public class CompletionException extends Exception {
    public CompletionException(String message) { super(message); }
}
