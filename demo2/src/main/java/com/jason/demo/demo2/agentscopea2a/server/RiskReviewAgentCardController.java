package com.jason.demo.demo2.agentscopea2a.server;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agentscope-a2a")
public class RiskReviewAgentCardController {

    private final AgentScopeA2aServer server;

    public RiskReviewAgentCardController(AgentScopeA2aServer server) {
        this.server = server;
    }

    @GetMapping(
            path = "/.well-known/agent-card.json",
            produces = "application/json;charset=UTF-8")
    public AgentCard agentCard() {
        return server.getAgentCard();
    }
}
