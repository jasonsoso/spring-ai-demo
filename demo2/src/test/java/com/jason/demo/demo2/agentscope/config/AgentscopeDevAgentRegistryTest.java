package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.rag.AgentscopeRagKnowledgeHolder;
import com.jason.demo.demo2.agentscope.rag.AgentscopeRagMode;
import com.jason.demo.demo2.agentscope.rag.AgentscopeRagProperties;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentscopeDevAgentRegistryTest {

    private static AgentscopeRagProperties defaults() {
        return new AgentscopeRagProperties(
                true,
                "agentscope-dev-knowledge.txt",
                3,
                0.3,
                false,
                "agentscope_dev_knowledge",
                1024,
                "",
                "https://open.bigmodel.cn/api/paas/v4",
                "embedding-2");
    }

    @Test
    void unavailableRag_fallsBackToNone() {
        HarnessAgent none = mock(HarnessAgent.class);
        AgentscopeRagKnowledgeHolder holder = AgentscopeRagKnowledgeHolder.unavailable(defaults());
        AtomicInteger builds = new AtomicInteger();
        AgentscopeDevAgentRegistry reg = new AgentscopeDevAgentRegistry(
                none,
                m -> {
                    builds.incrementAndGet();
                    return mock(HarnessAgent.class);
                },
                holder);
        assertThat(reg.get(AgentscopeRagMode.GENERIC)).isSameAs(none);
        assertThat(builds.get()).isZero();
    }

    @Test
    @SuppressWarnings("deprecation")
    void cachesAgentic() {
        HarnessAgent none = mock(HarnessAgent.class);
        Knowledge knowledge = mock(Knowledge.class);
        AgentscopeRagKnowledgeHolder holder = AgentscopeRagKnowledgeHolder.forTests(
                knowledge, RetrieveConfig.builder().limit(3).scoreThreshold(0.3).build());
        AtomicInteger builds = new AtomicInteger();
        HarnessAgent agentic = mock(HarnessAgent.class);
        AgentscopeDevAgentRegistry reg = new AgentscopeDevAgentRegistry(
                none,
                m -> {
                    builds.incrementAndGet();
                    assertThat(m).isEqualTo(AgentscopeRagMode.AGENTIC);
                    return agentic;
                },
                holder);
        assertThat(reg.get(AgentscopeRagMode.AGENTIC)).isSameAs(agentic);
        assertThat(reg.get(AgentscopeRagMode.AGENTIC)).isSameAs(agentic);
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    void noneAlwaysReturnsNoneAgent() {
        HarnessAgent none = mock(HarnessAgent.class);
        AgentscopeDevAgentRegistry reg = new AgentscopeDevAgentRegistry(
                none, m -> mock(HarnessAgent.class), AgentscopeRagKnowledgeHolder.unavailable(defaults()));
        assertThat(reg.get(AgentscopeRagMode.NONE)).isSameAs(none);
        assertThat(reg.get(null)).isSameAs(none);
    }
}
