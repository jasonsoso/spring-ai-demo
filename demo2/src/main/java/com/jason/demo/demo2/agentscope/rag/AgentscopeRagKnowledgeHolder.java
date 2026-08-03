package com.jason.demo.demo2.agentscope.rag;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 可选的 AgentScope Knowledge 持有者。装配失败时 {@link #available()} 为 false，Agent 回退 NONE。
 */
@SuppressWarnings("deprecation")
// Temporary: agentscope-extensions-rag-simple + deprecated Knowledge until AgentScope v2 RAG APIs.
// See docs/superpowers/specs/2026-08-03-agentscope-app-layer-rag-design.md §8
public final class AgentscopeRagKnowledgeHolder {

    private final Knowledge knowledge;
    private final RetrieveConfig retrieveConfig;
    private final boolean available;

    private AgentscopeRagKnowledgeHolder(
            Knowledge knowledge, RetrieveConfig retrieveConfig, boolean available) {
        this.knowledge = knowledge;
        this.retrieveConfig = retrieveConfig != null
                ? retrieveConfig
                : RetrieveConfig.builder().limit(3).scoreThreshold(0.3).build();
        this.available = available;
    }

    public static AgentscopeRagKnowledgeHolder unavailable(AgentscopeRagProperties rag) {
        RetrieveConfig cfg = RetrieveConfig.builder()
                .limit(Math.max(1, rag.topK()))
                .scoreThreshold(rag.similarityThreshold())
                .build();
        return new AgentscopeRagKnowledgeHolder(null, cfg, false);
    }

    public static AgentscopeRagKnowledgeHolder available(Knowledge knowledge, RetrieveConfig retrieveConfig) {
        if (knowledge == null) {
            throw new IllegalArgumentException("knowledge must not be null when available");
        }
        return new AgentscopeRagKnowledgeHolder(knowledge, retrieveConfig, true);
    }

    /** 测试用：标记为可用。 */
    public static AgentscopeRagKnowledgeHolder forTests(Knowledge knowledge, RetrieveConfig retrieveConfig) {
        return available(knowledge, retrieveConfig);
    }

    public boolean available() {
        return available;
    }

    public Knowledge knowledgeOrNull() {
        return knowledge;
    }

    public RetrieveConfig retrieveConfig() {
        return retrieveConfig;
    }

    /** 按 {@code ----} 切分知识库文本，去空白空块。 */
    public static List<String> splitChunks(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        for (String part : content.split("----")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                chunks.add(trimmed);
            }
        }
        return chunks;
    }
}
