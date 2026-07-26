package com.jason.demo.demo2.agentscope.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeDistributedPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableCfg.class);

    @Test
    void defaultEnabledIsTrueWhenPropertyMissing() {
        runner.run(ctx -> assertThat(ctx.getBean(AgentscopeDistributedProperties.class).enabled())
                .isTrue());
    }

    @Test
    void bindsEnabledFalse() {
        runner.withPropertyValues("app.agentscope.distributed.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(AgentscopeDistributedProperties.class).enabled())
                        .isFalse());
    }

    @EnableConfigurationProperties(AgentscopeDistributedProperties.class)
    static class EnableCfg {
    }
}
