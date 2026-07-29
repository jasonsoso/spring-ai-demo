package com.jason.demo.demo2.agentscopea2a;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencySmokeTest {

    @Test
    void agentscopeA2aTypesAreAvailable() {
        assertThat(ReActAgent.class).isNotNull();
        assertThat(A2aAgent.class).isNotNull();
        assertThat(WellKnownAgentCardResolver.class).isNotNull();
        assertThat(AgentScopeA2aServer.class).isNotNull();
    }
}
