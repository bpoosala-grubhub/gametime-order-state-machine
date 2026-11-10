package com.gametime.orders;

import java.util.UUID;

/** Payment port: a real processor could replace the demo adapter. */
public interface PaymentGateway {
    void authorize(UUID orderId) throws PaymentDeclinedException;
    void voidAuthorization(UUID orderId) throws PaymentVoidException;
}
