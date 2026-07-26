package com.jason.demo.demo2.agentscope.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;

public sealed interface AgentscopeDistributedBackend
        permits AgentscopeDistributedBackend.Local, AgentscopeDistributedBackend.Remote {

    AgentStateStore stateStore();

    record Local(AgentStateStore stateStore) implements AgentscopeDistributedBackend {
    }

    record Remote(DistributedStore distributedStore, AgentStateStore stateStore)
            implements AgentscopeDistributedBackend {
    }
}
