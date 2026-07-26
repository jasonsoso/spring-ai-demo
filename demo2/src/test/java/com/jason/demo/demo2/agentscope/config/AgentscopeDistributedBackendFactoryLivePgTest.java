package com.jason.demo.demo2.agentscope.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Test
    @EnabledIf("postgresReachable")
    void patchedBaseStore_put_doesNotThrowSqlSyntaxError() {
        assumeTrue(postgresReachable());

        AgentscopeDistributedBackend backend = AgentscopeDistributedBackendFactory.create(
                new AgentscopeDistributedProperties(true),
                new AgentScopeDataSourceProperties(
                        "jdbc:postgresql://127.0.0.1:5432/agentscope",
                        "agentscope",
                        "agentscope",
                        3000L));
        assumeTrue(backend instanceof AgentscopeDistributedBackend.Remote);
        var remote = (AgentscopeDistributedBackend.Remote) backend;

        String key = "patch-probe-" + UUID.randomUUID();
        remote.distributedStore()
                .baseStore()
                .put(List.of("demo2", "upsert-patch"), key, Map.of("ok", true));

        assertThat(remote.distributedStore().baseStore().get(List.of("demo2", "upsert-patch"), key))
                .isNotNull();
    }
}
