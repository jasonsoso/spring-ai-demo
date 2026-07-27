package com.example.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryPolicyTest {

    @Test
    void exponentialBackoffStartsAtBaseDelay() {
        RetryPolicy policy = new RetryPolicy(1000, 30_000);
        assertEquals(1000, policy.delayMillis(1));
        assertEquals(2000, policy.delayMillis(2));
        assertEquals(4000, policy.delayMillis(3));
    }
}
