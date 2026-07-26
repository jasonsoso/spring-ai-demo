package com.jason.demo.demo2.agentscope.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentscopeDistributedBackendFactoryLivePgTest {

    static boolean postgresReachable() {
        try (var ignored = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/agentscope",
                "agentscope",
                "agentscope")) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Test
    @EnabledIf("postgresReachable")
    void create_enabled_reachablePg_returnsPinnedRemote() {
        assumeTrue(postgresReachable());

        AgentscopeDistributedBackend backend = AgentscopeDistributedBackendFactory.create(
                new AgentscopeDistributedProperties(true),
                new AgentScopeDataSourceProperties(
                        "jdbc:postgresql://127.0.0.1:5432/agentscope",
                        "agentscope",
                        "agentscope",
                        3000L));

        assertThat(backend).isInstanceOf(AgentscopeDistributedBackend.Remote.class);
        AgentscopeDistributedBackend.Remote remote = (AgentscopeDistributedBackend.Remote) backend;
        assertThat(remote.stateStore()).isSameAs(remote.distributedStore().agentStateStore());
    }
}
