package com.jason.demo.demo2.agentscope.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

public final class AgentscopeDistributedBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeDistributedBackendFactory.class);

    private AgentscopeDistributedBackendFactory() {
    }

    public static AgentscopeDistributedBackend create(
            AgentscopeDistributedProperties distributed,
            AgentScopeDataSourceProperties dsProps) {
        if (!distributed.enabled()) {
            log.info("AgentScope distributed=off (memory stateStore + local workspace)");
            return new AgentscopeDistributedBackend.Local(new InMemoryAgentStateStore());
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dsProps.url());
        config.setUsername(dsProps.username());
        config.setPassword(dsProps.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(dsProps.connectionTimeoutMs());
        config.setPoolName("agentscope-postgres");
        config.setInitializationFailTimeout(-1);

        HikariDataSource dataSource = new HikariDataSource(config);
        try (Connection ignored = dataSource.getConnection()) {
            JdbcDistributedStore created = JdbcDistributedStore.create(dataSource);
            // Pin instances: sandboxExecutionGuard() is a new object on every call.
            // Builder must keep snapshot+guard or they become Noop.
            var stateStore = created.agentStateStore();
            var baseStore = created.baseStore();
            DistributedStore pinned = DistributedStore.builder()
                    .agentStateStore(stateStore)
                    .baseStore(baseStore)
                    .sandboxSnapshotSpec(created.sandboxSnapshotSpec())
                    .sandboxExecutionGuard(created.sandboxExecutionGuard())
                    .build();
            log.info("AgentScope distributed=jdbc url={}", dsProps.url());
            return new AgentscopeDistributedBackend.Remote(pinned, stateStore);
        } catch (Exception ex) {
            log.warn(
                    "AgentScope PostgreSQL unreachable; distributed=local fallback. reason={}",
                    ex.toString());
            try {
                dataSource.close();
            } catch (Exception closeEx) {
                log.debug("Failed to close agentscope DataSource after probe failure", closeEx);
            }
            return new AgentscopeDistributedBackend.Local(new InMemoryAgentStateStore());
        }
    }
}
