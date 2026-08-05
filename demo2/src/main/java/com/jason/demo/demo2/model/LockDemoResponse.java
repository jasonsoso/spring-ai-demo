package com.jason.demo.demo2.model;

public record LockDemoResponse(
        boolean locked,
        String key,
        Long elapsedMs,
        String echo,
        String reason) {

    public static LockDemoResponse ok(String key, long elapsedMs, String echo) {
        return new LockDemoResponse(true, key, elapsedMs, echo, null);
    }

    public static LockDemoResponse conflict() {
        return new LockDemoResponse(false, null, null, null, "duplicate_in_progress");
    }
}
