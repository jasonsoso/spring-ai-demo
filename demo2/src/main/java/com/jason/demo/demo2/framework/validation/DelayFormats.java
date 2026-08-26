package com.jason.demo.demo2.framework.validation;

import java.time.Duration;
import java.util.Optional;

public final class DelayFormats {

    private DelayFormats() {
    }

    public static Optional<Duration> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                return Optional.of(Duration.parse(value));
            }
            if (value.endsWith("ms")) {
                return Optional.of(Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2))));
            }
            if (value.endsWith("s")) {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1))));
            }
            if (value.endsWith("m")) {
                return Optional.of(Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1))));
            }
            if (value.endsWith("h")) {
                return Optional.of(Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1))));
            }
            return Optional.of(Duration.parse(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
