package com.jason.demo.demo2.agentscopea2a.client;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class RiskReviewTool {

    private final RemoteRiskReviewService service;

    public RiskReviewTool(RemoteRiskReviewService service) {
        this.service = service;
    }

    @Tool(
            name = "risk_review",
            description = "Call the independent Java risk review Agent with the user's explicit change description. It does not read project files.",
            readOnly = true)
    public String review(
            @ToolParam(
                    name = "change_description",
                    description = "The complete change description explicitly provided by the user.",
                    required = true)
            String changeDescription) {
        return service.review(changeDescription).block();
    }
}
