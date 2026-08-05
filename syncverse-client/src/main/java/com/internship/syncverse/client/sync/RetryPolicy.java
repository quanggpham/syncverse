package com.internship.syncverse.client.sync;

import java.time.Duration;

public final class RetryPolicy {

    private final Duration initial;
    private final Duration maximum;

    private RetryPolicy(Duration initial, Duration maximum) {
        if (initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException("Initial delay must be positive");
        }
        if (maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("Maximum delay must not be less than initial delay");
        }
        this.initial = initial;
        this.maximum = maximum;
    }

    public static RetryPolicy exponential(Duration initial, Duration maximum) {
        return new RetryPolicy(initial, maximum);
    }

    public Duration delay(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("Attempt cannot be negative");
        }
        Duration delay = initial;
        for (int index = 0; index < attempt && delay.compareTo(maximum) < 0; index++) {
            if (delay.compareTo(maximum.dividedBy(2)) > 0) {
                return maximum;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximum) > 0 ? maximum : delay;
    }
}
