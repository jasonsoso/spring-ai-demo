package com.jason.demo.demo2.service;

public final class AskUserSessionHolder {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private AskUserSessionHolder() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
