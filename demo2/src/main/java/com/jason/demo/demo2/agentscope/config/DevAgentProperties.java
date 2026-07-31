package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
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
        @Valid ModelFallback modelFallback,
        @Valid McpSettings mcp,
        @Valid Memory memory,
        @Valid Sandbox sandbox) {

    static final String DEFAULT_FLUSH_PROMPT = """
            从对话中提取以后仍然有用的项目约定、用户偏好、技术决定和待办事项。
            忽略寒暄、临时状态、工具调用细节以及已经存在的重复信息。
            不记录密码、令牌、密钥、手机号、邮箱等敏感信息。
            只输出 Markdown 列表；没有值得保存的信息时，只输出 NO_REPLY。
            """;

    static final String DEFAULT_CONSOLIDATION_PROMPT = """
            把现有 MEMORY.md 和新增的每日记忆整理成一份完整的长期记忆。
            合并重复信息；新决定覆盖已经失效的旧决定；删除寒暄、临时状态和敏感信息。
            最终内容不超过 %d tokens，约 %d 个字符。
            只输出整理后的完整 MEMORY.md，不要解释整理过程。
            """;

    public DevAgentProperties {
        if (modelFallback == null) {
            modelFallback = new ModelFallback(
                    new Model("", "https://api.moonshot.cn/v1", "kimi-k3"),
                    2);
        }
        if (mcp == null) {
            mcp = new McpSettings(false, List.of());
        }
        if (memory == null) {
            memory = new Memory(
                    false,
                    true,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(30),
                    4000,
                    DEFAULT_FLUSH_PROMPT,
                    DEFAULT_CONSOLIDATION_PROMPT);
        }
        if (sandbox == null) {
            sandbox = Sandbox.disabledDefaults();
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

    /**
     * 主模型失败后的备用模型；apiKey 允许为空（未配置时仅重试主模型）。
     * maxAttempts 表示单次调用该模型最多尝试次数（含首次）。
     */
    public record ModelFallback(
            @Valid Model fallback,
            @Min(1) int maxAttempts) {
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

    public record Memory(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("true") boolean saveRequiresConfirm,
            Duration flushMinGap,
            Duration consolidationMinGap,
            Integer consolidationMaxTokens,
            String flushPrompt,
            String consolidationPrompt) {

        public Memory {
            if (flushMinGap == null) {
                flushMinGap = Duration.ofMinutes(10);
            }
            if (consolidationMinGap == null) {
                consolidationMinGap = Duration.ofMinutes(30);
            }
            if (consolidationMaxTokens == null || consolidationMaxTokens < 1) {
                consolidationMaxTokens = 4000;
            }
            if (flushPrompt == null || flushPrompt.isBlank()) {
                flushPrompt = DEFAULT_FLUSH_PROMPT;
            }
            if (consolidationPrompt == null || consolidationPrompt.isBlank()) {
                consolidationPrompt = DEFAULT_CONSOLIDATION_PROMPT;
            }
        }
    }

    public record Sandbox(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("agentscope-java-sandbox:17") String image,
            @DefaultValue("none") String network,
            @DefaultValue("/workspace") String workspaceRoot,
            @DefaultValue(".agentscope/sandbox-snapshots") String snapshotRoot,
            @DefaultValue("536870912") long memorySizeBytes,
            @DefaultValue("1") long cpuCount) {

        public Sandbox {
            if (enabled) {
                if (image == null || image.isBlank()) {
                    throw new IllegalArgumentException("sandbox.image must not be blank when enabled");
                }
                if (network == null || network.isBlank()) {
                    throw new IllegalArgumentException("sandbox.network must not be blank when enabled");
                }
                if (workspaceRoot == null || workspaceRoot.isBlank()) {
                    throw new IllegalArgumentException(
                            "sandbox.workspace-root must not be blank when enabled");
                }
                // Local 降级才用本机快照目录；Remote（PostgresSnapshot）忽略此字段。
                if (snapshotRoot == null || snapshotRoot.isBlank()) {
                    snapshotRoot = ".agentscope/sandbox-snapshots";
                }
                if (memorySizeBytes <= 0) {
                    throw new IllegalArgumentException(
                            "sandbox.memory-size-bytes must be > 0 when enabled");
                }
                if (cpuCount <= 0) {
                    throw new IllegalArgumentException("sandbox.cpu-count must be > 0 when enabled");
                }
            }
        }

        static Sandbox disabledDefaults() {
            return new Sandbox(
                    false,
                    "agentscope-java-sandbox:17",
                    "none",
                    "/workspace",
                    ".agentscope/sandbox-snapshots",
                    536870912L,
                    1L);
        }
    }
}
