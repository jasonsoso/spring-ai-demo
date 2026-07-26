package com.jason.demo.demo2.agentscope.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.postgresql.PostgresDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
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
            PostgresDistributedStore created = PostgresDistributedStore.create(dataSource);
            // Pin instances: PostgresDistributedStore.agentStateStore()/baseStore() create new objects each call.
            var stateStore = created.agentStateStore();
            BaseStore baseStore = created.baseStore();
            DistributedStore pinned = DistributedStore.builder()
                    .agentStateStore(stateStore)
                    .baseStore(baseStore)
                    .build();
            log.info("AgentScope distributed=postgres url={}", dsProps.url());
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
