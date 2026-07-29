package com.jason.demo.demo2.agentscopea2a.server;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RiskReviewAgentCardControllerTest {

    @Test
    void exposesAgentCardUnderDedicatedPrefix() throws Exception {
        ReActAgent.Builder agentBuilder = ReActAgent.builder()
                .name("risk-review-agent")
                .description("审查 Java 改动风险")
                .sysPrompt("只根据输入回答")
                .model(mock(Model.class))
                .toolkit(new Toolkit())
                .maxIters(1);
        ConfigurableAgentCard card = new ConfigurableAgentCard.Builder()
                .name("risk-review-agent")
                .description("审查 Java 改动风险")
                .url("http://localhost:8081/agentscope-a2a")
                .version("1.0.0")
                .defaultInputModes(java.util.List.of("text"))
                .defaultOutputModes(java.util.List.of("text"))
                .preferredTransport("JSONRPC")
                .build();
        AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
                .agentCard(card)
                .deploymentProperties(new DeploymentProperties("localhost", 8081, "/agentscope-a2a"))
                .agentExecuteProperties(
                        AgentExecuteProperties.builder().completeWithMessage(true).build())
                .build();

        MockMvc mvc = standaloneSetup(new RiskReviewAgentCardController(server)).build();

        mvc.perform(get("/agentscope-a2a/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.name").value("risk-review-agent"))
                .andExpect(jsonPath("$.url").value("http://localhost:8081/agentscope-a2a"));
    }
}
