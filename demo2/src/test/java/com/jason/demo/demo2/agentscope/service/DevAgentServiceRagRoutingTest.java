package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.AgentscopeDevAgentRegistry;
import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.rag.AgentscopeRagKnowledgeHolder;
import com.jason.demo.demo2.agentscope.rag.AgentscopeRagMode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevAgentServiceRagRoutingTest {

    @Test
    @SuppressWarnings("deprecation")
    void ask_routesGenericToRegistry() {
        HarnessAgent none = mock(HarnessAgent.class);
        HarnessAgent generic = mock(HarnessAgent.class);
        when(generic.streamEvents(any(String.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());
        Knowledge knowledge = mock(Knowledge.class);
        AgentscopeRagKnowledgeHolder holder = AgentscopeRagKnowledgeHolder.forTests(
                knowledge, RetrieveConfig.builder().limit(3).scoreThreshold(0.3).build());
        AtomicReference<AgentscopeRagMode> built = new AtomicReference<>();
        AgentscopeDevAgentRegistry registry = new AgentscopeDevAgentRegistry(
                none,
                mode -> {
                    built.set(mode);
                    return generic;
                },
                holder);

        DevAgentProperties properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                ".",
                "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                null,
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                null);

        DevAgentService service = new DevAgentService(
                registry, properties, mock(AgentStateStore.class), mock(Tracer.class));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi", "GENERIC")))
                .thenConsumeWhile(e -> true)
                .verifyComplete();

        assertThat(built.get()).isEqualTo(AgentscopeRagMode.GENERIC);
        verify(generic).streamEvents(eq("hi"), any(RuntimeContext.class));
    }
}
