package com.jason.demo.demo2.embabel.model;

public record AgentResponse(
        String processId,
        String agentName,
        String outputType,
        Object output) {
}
