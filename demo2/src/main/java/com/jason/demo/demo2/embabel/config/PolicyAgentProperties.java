package com.jason.demo.demo2.embabel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.policy-agent")
public record PolicyAgentProperties(Prompts prompts) {

    public record Prompts(String extractPolicyQuestion, String answer) {
    }
}
