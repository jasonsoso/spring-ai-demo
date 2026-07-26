package com.jason.demo.demo2.agentscope.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentscopeDistributedBackendFactoryTest {

    private static AgentScopeDataSourceProperties unreachableDs() {
        return new AgentScopeDataSourceProperties(
                "jdbc:postgresql://127.0.0.1:1/agentscope",
                "agentscope",
                "agentscope",
                1000L);
    }

    @Test
    void create_disabled_returnsLocalInMemory_withoutNeedingReachablePg() {
        AgentscopeDistributedBackend backend = AgentscopeDistributedBackendFactory.create(
                new AgentscopeDistributedProperties(false),
                unreachableDs());

        assertThat(backend).isInstanceOf(AgentscopeDistributedBackend.Local.class);
        assertThat(backend.stateStore()).isInstanceOf(InMemoryAgentStateStore.class);
    }

    @Test
    void create_enabled_unreachableHost_fallsBackToLocalInMemory() {
        AgentscopeDistributedBackend backend = AgentscopeDistributedBackendFactory.create(
                new AgentscopeDistributedProperties(true),
                unreachableDs());

        assertThat(backend).isInstanceOf(AgentscopeDistributedBackend.Local.class);
        assertThat(backend.stateStore()).isInstanceOf(InMemoryAgentStateStore.class);
    }

    @Test
    void remoteBackend_stateStoreMatchesDistributedStore() {
        AgentStateStore state = new InMemoryAgentStateStore();
        DistributedStore store = DistributedStore.builder()
                .agentStateStore(state)
                .baseStore(mock(BaseStore.class))
                .build();
        AgentscopeDistributedBackend.Remote remote =
                new AgentscopeDistributedBackend.Remote(store, state);

        assertThat(remote.stateStore()).isSameAs(store.agentStateStore());
        assertThat(remote.stateStore()).isSameAs(state);
    }

    @Test
    void fixUpsertTypo_removesDoubleCommaFromAgentscope200Sql() {
        String broken = """
                INSERT INTO agentscope_store (...)
                ON CONFLICT DO UPDATE SET
                    version    = agentscope_store.version + 1,,
                    updated_at = EXCLUDED.updated_at
                """;

        String fixed = AgentscopeDistributedBackendFactory.fixUpsertTypo(broken);

        assertThat(fixed).doesNotContain(AgentscopeDistributedBackendFactory.BROKEN_UPSERT_FRAGMENT);
        assertThat(fixed).contains("version    = agentscope_store.version + 1,");
    }

    @Test
    void fixUpsertTypo_leavesAlreadyFixedSqlUnchanged() {
        String ok = "version = agentscope_store.version + 1,\nupdated_at = EXCLUDED.updated_at";

        assertThat(AgentscopeDistributedBackendFactory.fixUpsertTypo(ok)).isEqualTo(ok);
    }
}
