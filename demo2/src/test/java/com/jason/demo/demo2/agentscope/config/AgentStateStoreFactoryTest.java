package com.jason.demo.demo2.agentscope.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStateStoreFactoryTest {

    @Test
    void create_unreachableHost_fallsBackToInMemory() {
        AgentScopeDataSourceProperties props = new AgentScopeDataSourceProperties(
                "jdbc:postgresql://127.0.0.1:1/agentscope",
                "agentscope",
                "agentscope",
                1000L);

        AgentStateStore store = AgentStateStoreFactory.create(props);

        assertThat(store).isInstanceOf(InMemoryAgentStateStore.class);
    }
}
