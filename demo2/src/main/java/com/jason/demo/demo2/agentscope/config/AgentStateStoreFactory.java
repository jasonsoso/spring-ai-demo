package com.jason.demo.demo2.agentscope.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

public final class AgentStateStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentStateStoreFactory.class);

    private AgentStateStoreFactory() {
    }

    public static AgentStateStore create(AgentScopeDataSourceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.url());
        config.setUsername(props.username());
        config.setPassword(props.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(props.connectionTimeoutMs());
        config.setPoolName("agentscope-postgres");
        config.setInitializationFailTimeout(-1);

        HikariDataSource dataSource = new HikariDataSource(config);
        try (Connection ignored = dataSource.getConnection()) {
            AgentStateStore store = PostgresAgentStateStore.builder(dataSource)
                    .createIfNotExist(true)
                    .build();
            log.info("AgentScope stateStore=postgres url={}", props.url());
            return store;
        } catch (Exception ex) {
            log.warn(
                    "AgentScope PostgreSQL unreachable; stateStore=memory. reason={}",
                    ex.toString());
            try {
                dataSource.close();
            } catch (Exception closeEx) {
                log.debug("Failed to close agentscope DataSource after probe failure", closeEx);
            }
            return new InMemoryAgentStateStore();
        }
    }
}
