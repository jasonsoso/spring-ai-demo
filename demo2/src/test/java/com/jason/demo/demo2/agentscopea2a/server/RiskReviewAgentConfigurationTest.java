package com.jason.demo.demo2.agentscopea2a.server;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RiskReviewAgentConfigurationTest {

    @Test
    void createsIndependentReadOnlyRiskReviewAgentBuilder() {
        RiskReviewAgentProperties properties = new RiskReviewAgentProperties(
                "risk-review-agent",
                "审查 Java 改动风险",
                "1.0.0",
                """
                        你是独立的 Java 风险审查 Agent。
                        只根据用户提供的改动说明回答。
                        固定输出 ## 结论、## 风险、## 建议。
                        """);

        ReActAgent agent = new RiskReviewAgentConfiguration()
                .riskReviewAgentBuilder(mock(Model.class), properties)
                .build();

        try {
            assertThat(agent.getName()).isEqualTo("risk-review-agent");
            assertThat(agent.getDescription()).isEqualTo("审查 Java 改动风险");
            assertThat(agent.getToolkit().getToolNames()).isEmpty();
        } finally {
            agent.close();
        }
    }

    @Test
    void applicationPropertiesKeepAgentCardChineseText() throws IOException {
        String description = PropertiesLoaderUtils
                .loadProperties(new ClassPathResource("application.properties"))
                .getProperty("app.agentscopea2a.server.description");

        assertThat(description).startsWith("审查 Java");
    }
}
