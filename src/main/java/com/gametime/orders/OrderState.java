package com.gametime.orders;

import java.util.Locale;

/** The only order states required by the assessment. */
public enum OrderState {
    INITIALIZED,
    PAYMENT_AUTHORIZED,
    COMPLETE,
    REJECTED,
    CANCELLED,
    NEEDS_ATTENTION;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
