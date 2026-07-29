package com.jason.demo.demo2.agentscopea2a.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.agentscopea2a.client")
public record RiskReviewClientProperties(
        @NotBlank String name,
        @NotBlank String baseUrl,
        @DefaultValue("60s") Duration timeout,
        @Min(1) @Max(100_000) int maxMessageLength) {
}
