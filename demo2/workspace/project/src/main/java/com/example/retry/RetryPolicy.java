package com.example.retry;

public final class RetryPolicy {

    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public RetryPolicy(long baseDelayMillis, long maxDelayMillis) {
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    public long delayMillis(int attempt) {
        long delay = baseDelayMillis * (1L << attempt);
        return Math.min(delay, maxDelayMillis);
    }
}
