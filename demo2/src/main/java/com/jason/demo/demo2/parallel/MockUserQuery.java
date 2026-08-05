package com.jason.demo.demo2.parallel;

import com.jason.demo.demo2.model.UserProfileDto;
import org.springframework.stereotype.Component;

@Component
public class MockUserQuery {

    public UserProfileDto find(String userId, long delayMs, boolean fail) {
        delay(delayMs);
        if (fail) {
            throw new IllegalStateException("mock user query failed");
        }
        String id = (userId == null || userId.isBlank()) ? "u1" : userId.strip();
        return new UserProfileDto(id, "Alice");
    }

    private static void delay(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
