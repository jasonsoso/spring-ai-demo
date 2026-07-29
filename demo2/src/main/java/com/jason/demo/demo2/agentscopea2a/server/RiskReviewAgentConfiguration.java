package com.jason.demo.demo2.agentscopea2a.server;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(RiskReviewAgentProperties.class)
public class RiskReviewAgentConfiguration {

    @Bean
    ReActAgent.Builder riskReviewAgentBuilder(
            @Qualifier("agentscopeDeepSeekModel") Model model,
            RiskReviewAgentProperties properties) {
        return ReActAgent.builder()
                .name(properties.name())
                .description(properties.description())
                .sysPrompt(properties.systemPrompt())
                .model(model)
                .toolkit(new Toolkit())
                .maxIters(4);
    }

    @Bean
    ConfigurableAgentCard riskReviewAgentCard(
            RiskReviewAgentProperties properties,
            @Value("${server.port:8081}") int port) {
        return new ConfigurableAgentCard.Builder()
                .name(properties.name())
                .description(properties.description())
                .url("http://localhost:" + port + "/agentscope-a2a")
                .version(properties.version())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .preferredTransport("JSONRPC")
                .build();
    }

    @Bean
    AgentScopeA2aServer riskReviewA2aServer(
            ReActAgent.Builder agentBuilder,
            ConfigurableAgentCard agentCard,
            @Value("${server.port:8081}") int port) {
        return AgentScopeA2aServer.builder(agentBuilder)
                .agentCard(agentCard)
                .deploymentProperties(
                        new DeploymentProperties("localhost", port, "/agentscope-a2a"))
                .agentExecuteProperties(
                        AgentExecuteProperties.builder()
                                .completeWithMessage(true)
                                .build())
                .build();
    }
}
