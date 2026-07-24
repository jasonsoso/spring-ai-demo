package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.dev-agent")
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Compaction compaction,
        @Valid Model model,
        @Valid McpSettings mcp) {

    public DevAgentProperties {
        if (mcp == null) {
            mcp = new McpSettings(false, List.of());
        }
    }

    public record Compaction(
            @Min(2) int triggerMessages,
            @Min(1) int keepMessages,
            @NotBlank String summaryPrompt) {
    }

    /**
     * apiKey 允许为空：缺 DEEPSEEK_API_KEY 时不阻止应用启动，由 Service 在 ask 时返回 ERROR。
     */
    public record Model(
            String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String name) {
    }

    public record McpSettings(
            @DefaultValue("false") boolean enabled,
            @DefaultValue List<@Valid McpClientConfig> clients) {

        public McpSettings {
            if (clients == null) {
                clients = List.of();
            }
        }
    }

    public record McpClientConfig(
            @NotBlank String name,
            @DefaultValue("true") boolean enabled,
            @NotBlank String command,
            @NotEmpty List<@NotBlank String> arguments,
            String root,
            @NotEmpty List<@NotBlank String> enabledTools) {
    }
}
