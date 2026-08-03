package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.rag.AgentscopeRagKnowledgeHolder;
import com.jason.demo.demo2.agentscope.rag.AgentscopeRagMode;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 按 {@link AgentscopeRagMode} 返回 HarnessAgent；GENERIC/AGENTIC 懒加载缓存。
 * Knowledge 不可用时回退 NONE 实例。
 */
public final class AgentscopeDevAgentRegistry {

    private final HarnessAgent noneAgent;
    private final Function<AgentscopeRagMode, HarnessAgent> ragAgentFactory;
    private final AgentscopeRagKnowledgeHolder ragHolder;
    private final ConcurrentMap<AgentscopeRagMode, HarnessAgent> cache = new ConcurrentHashMap<>();

    public AgentscopeDevAgentRegistry(
            HarnessAgent noneAgent,
            Function<AgentscopeRagMode, HarnessAgent> ragAgentFactory,
            AgentscopeRagKnowledgeHolder ragHolder) {
        this.noneAgent = Objects.requireNonNull(noneAgent, "noneAgent");
        this.ragAgentFactory = Objects.requireNonNull(ragAgentFactory, "ragAgentFactory");
        this.ragHolder = Objects.requireNonNull(ragHolder, "ragHolder");
    }

    public HarnessAgent get(AgentscopeRagMode mode) {
        AgentscopeRagMode resolved = mode == null ? AgentscopeRagMode.NONE : mode;
        if (resolved == AgentscopeRagMode.NONE) {
            return noneAgent;
        }
        if (!ragHolder.available()) {
            return noneAgent;
        }
        return cache.computeIfAbsent(resolved, ragAgentFactory);
    }
}
