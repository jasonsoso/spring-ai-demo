package com.jason.demo.demo2.agentscope.model;

import jakarta.validation.constraints.NotBlank;

public record ApplyDiffRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String diffId,
        boolean approved) {
}
