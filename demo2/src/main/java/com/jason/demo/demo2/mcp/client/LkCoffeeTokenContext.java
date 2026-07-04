package com.jason.demo.demo2.mcp.client;

public final class LkCoffeeTokenContext {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private LkCoffeeTokenContext() {}

    public static void set(String token) {
        TOKEN.set(token);
    }

    public static String get() {
        return TOKEN.get();
    }

    public static void clear() {
        TOKEN.remove();
    }
}
