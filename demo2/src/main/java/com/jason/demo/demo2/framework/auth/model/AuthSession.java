package com.jason.demo.demo2.framework.auth.model;

import java.time.LocalDateTime;

public record AuthSession(
        String token,
        Long memberId,
        String phone,
        String avatarUrl,
        LocalDateTime loginAt,
        long expiresInSeconds) {
}
