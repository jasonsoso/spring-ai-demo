package com.jason.demo.demo2.product.app.listener;

import java.net.InetAddress;

public final class OutboxConsumerNames {

    private OutboxConsumerNames() {
    }

    public static String unique(String prefix, String hostname, long pid) {
        String host = hostname == null || hostname.isBlank() ? "unknown" : hostname.trim();
        return prefix + "-" + host + "-" + pid;
    }

    public static String forThisProcess(String prefix) {
        return unique(prefix, hostname(), ProcessHandle.current().pid());
    }

    static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
