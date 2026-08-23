package com.jason.demo.demo2.lock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class LockKeys {

    private LockKeys() {}

    public static String messageHash(String message) {
        String raw = message == null ? "" : message;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String demoSubmitKey(String userId, String sessionId, String message) {
        return "demo:lock:submit:" + userId + ":" + sessionId + ":" + messageHash(message);
    }

    public static String devAgentAskKey(String userId, String sessionId, String message) {
        return "agentscope:dev-agent:ask:" + userId + ":" + sessionId + ":" + messageHash(message);
    }

    public static String delayScannerFallbackKey() {
        return "delay:scanner:fallback";
    }
}
