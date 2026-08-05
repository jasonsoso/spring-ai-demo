package com.jason.demo.demo2.parallel;

import com.jason.demo.demo2.model.OrderDto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockOrderQuery {

    public List<OrderDto> findByUserId(String userId, long delayMs, boolean fail) {
        delay(delayMs);
        if (fail) {
            throw new IllegalStateException("mock order query failed");
        }
        String id = (userId == null || userId.isBlank()) ? "u1" : userId.strip();
        return List.of(new OrderDto("o-" + id + "-1", 99.0));
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
