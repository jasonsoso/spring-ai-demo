package com.jason.demo.demo2.agentscope.rag;

/**
 * AgentScope Harness Tab 的 RAG 模式。对应官方 {@code RAGMode}，另加 {@link #NONE}。
 */
public enum AgentscopeRagMode {
    NONE,
    GENERIC,
    AGENTIC;

    /**
     * 解析请求中的 ragMode；null / blank / 非法值一律 {@link #NONE}。
     */
    public static AgentscopeRagMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return AgentscopeRagMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
