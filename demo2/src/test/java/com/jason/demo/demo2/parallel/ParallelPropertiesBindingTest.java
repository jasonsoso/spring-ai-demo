package com.jason.demo.demo2.parallel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableParallelProps.class);

    @EnableConfigurationProperties(ParallelProperties.class)
    static class EnableParallelProps {
    }

    @Test
    void bindsDefaultsWhenUnset() {
        runner.run(ctx -> {
            ParallelProperties props = ctx.getBean(ParallelProperties.class);
            assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(props.getJdk8().getCorePoolSize()).isEqualTo(0);
            assertThat(props.getJdk8().getMaxPoolSize()).isEqualTo(0);
            assertThat(props.getJdk8().getKeepAlive()).isEqualTo(Duration.ofSeconds(60));
            assertThat(props.getJdk8().getQueueCapacity()).isEqualTo(200);
            assertThat(props.getJdk8().getRejectedPolicy()).isEqualTo("caller_runs");
        });
    }

    @Test
    void bindsOverrides() {
        runner.withPropertyValues(
                "demo.parallel.timeout=2s",
                "demo.parallel.jdk8.core-pool-size=4",
                "demo.parallel.jdk8.max-pool-size=8",
                "demo.parallel.jdk8.queue-capacity=50",
                "demo.parallel.jdk8.rejected-policy=abort"
        ).run(ctx -> {
            ParallelProperties props = ctx.getBean(ParallelProperties.class);
            assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(props.getJdk8().getCorePoolSize()).isEqualTo(4);
            assertThat(props.getJdk8().getMaxPoolSize()).isEqualTo(8);
            assertThat(props.getJdk8().getQueueCapacity()).isEqualTo(50);
            assertThat(props.getJdk8().getRejectedPolicy()).isEqualTo("abort");
        });
    }
}
