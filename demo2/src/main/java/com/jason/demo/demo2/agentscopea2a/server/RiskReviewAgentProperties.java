package com.jason.demo.demo2.agentscopea2a.server;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agentscopea2a.server")
public record RiskReviewAgentProperties(
        @NotBlank String name,
        @NotBlank String description,
        @NotBlank String version,
        @NotBlank String systemPrompt) {
}
