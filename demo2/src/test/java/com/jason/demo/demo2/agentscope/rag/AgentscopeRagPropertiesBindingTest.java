package com.jason.demo.demo2.agentscope.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeRagPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "app.agentscope.rag.enabled=true",
                    "app.agentscope.rag.knowledge-file=agentscope-dev-knowledge.txt",
                    "app.agentscope.rag.top-k=5",
                    "app.agentscope.rag.similarity-threshold=0.25",
                    "app.agentscope.rag.reindex-on-startup=true",
                    "app.agentscope.rag.table-name=agentscope_dev_knowledge",
                    "app.agentscope.rag.embedding-dimensions=1024",
                    "app.agentscope.rag.embedding-api-key=test-key",
                    "app.agentscope.rag.embedding-base-url=https://open.bigmodel.cn/api/paas/v4",
                    "app.agentscope.rag.embedding-model=embedding-2");

    @Test
    void bindsRagProperties() {
        runner.run(ctx -> {
            AgentscopeRagProperties props = ctx.getBean(AgentscopeRagProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.topK()).isEqualTo(5);
            assertThat(props.similarityThreshold()).isEqualTo(0.25);
            assertThat(props.reindexOnStartup()).isTrue();
            assertThat(props.tableName()).isEqualTo("agentscope_dev_knowledge");
            assertThat(props.embeddingApiKey()).isEqualTo("test-key");
            assertThat(props.embeddingModel()).isEqualTo("embedding-2");
        });
    }

    @Test
    void defaultsWhenMinimal() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    AgentscopeRagProperties props = ctx.getBean(AgentscopeRagProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.topK()).isEqualTo(3);
                    assertThat(props.knowledgeFile()).isEqualTo("agentscope-dev-knowledge.txt");
                    assertThat(props.embeddingDimensions()).isEqualTo(1024);
                });
    }

    @EnableConfigurationProperties(AgentscopeRagProperties.class)
    static class TestConfig {
    }
}
