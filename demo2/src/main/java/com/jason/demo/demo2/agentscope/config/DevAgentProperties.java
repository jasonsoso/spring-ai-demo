package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.dev-agent")
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Model model) {

    /**
     * apiKey 允许为空：缺 DEEPSEEK_API_KEY 时不阻止应用启动，由 Service 在 ask 时返回 ERROR。
     */
    public record Model(
            String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String name) {
    }
}
