package com.jason.demo.demo2.agentscope.model;

import jakarta.validation.constraints.NotBlank;

public record DevAgentRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message) {
}
