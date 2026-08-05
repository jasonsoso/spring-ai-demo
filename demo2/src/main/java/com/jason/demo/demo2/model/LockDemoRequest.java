package com.jason.demo.demo2.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record LockDemoRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message,
        @Min(1) @Max(20000) Integer workMs) {}
