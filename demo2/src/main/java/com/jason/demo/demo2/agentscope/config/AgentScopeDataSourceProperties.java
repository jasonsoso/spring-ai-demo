package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.datasource")
public record AgentScopeDataSourceProperties(
        @NotBlank String url,
        @NotBlank String username,
        String password,
        long connectionTimeoutMs) {

    public AgentScopeDataSourceProperties {
        if (connectionTimeoutMs <= 0) {
            connectionTimeoutMs = 3000L;
        }
        if (password == null) {
            password = "";
        }
    }
}
