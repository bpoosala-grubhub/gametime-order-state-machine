package com.gametime.orders;

import java.time.Instant;

/** Immutable audit record of a single state change, including creation. */
public record StateChange(OrderState from, OrderState to, Instant at, String reason) { }
