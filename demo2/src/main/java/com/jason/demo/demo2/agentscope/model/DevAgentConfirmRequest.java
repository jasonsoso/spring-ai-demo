package com.jason.demo.demo2.agentscope.model;

import jakarta.validation.constraints.NotBlank;

public record DevAgentConfirmRequest(
        String userId,
        @NotBlank String sessionId,
        boolean approved) {
}
