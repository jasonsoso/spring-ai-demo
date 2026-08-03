package com.jason.demo.demo2.agentscope.model;

import jakarta.validation.constraints.NotBlank;

public record DevAgentRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message,
        String ragMode) {

    /** 兼容旧调用：ragMode 缺省为 null（按 NONE 解析）。 */
    public DevAgentRequest(String userId, String sessionId, String message) {
        this(userId, sessionId, message, null);
    }
}
