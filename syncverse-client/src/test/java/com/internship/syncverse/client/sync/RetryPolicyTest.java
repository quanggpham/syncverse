package com.internship.syncverse.client.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {

    @Test
    void backoffCapsAtThirtySeconds() {
        RetryPolicy policy = RetryPolicy.exponential(
                Duration.ofSeconds(1), Duration.ofSeconds(30));

        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L),
                IntStream.range(0, 7)
                        .mapToObj(i -> policy.delay(i).toSeconds())
                        .toList());
    }

    @Test
    void rejectsNegativeAttempt() {
        RetryPolicy policy = RetryPolicy.exponential(
                Duration.ofSeconds(1), Duration.ofSeconds(30));

        assertThrows(IllegalArgumentException.class, () -> policy.delay(-1));
    }
}
