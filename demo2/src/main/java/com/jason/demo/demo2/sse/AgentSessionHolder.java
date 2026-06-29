package com.jason.demo.demo2.sse;

public final class AgentSessionHolder {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private AgentSessionHolder() {
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
