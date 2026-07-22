package com.jason.demo.demo2.config;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在 AgentScope {@link Model} 层打印 request / 聚合 response，
 * 对齐 {@link LoggingChatModel}（覆盖不经 Spring AI Advisor 的 AgentScope 调用）。
 */
public final class LoggingAgentscopeModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(LoggingAgentscopeModel.class);

    private final Model delegate;
    private final String label;

    public LoggingAgentscopeModel(Model delegate, String label) {
        this.delegate = delegate;
        this.label = label == null || label.isBlank() ? "agentscope-model" : label;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (!log.isDebugEnabled()) {
            return delegate.stream(messages, tools, options);
        }

        log.debug("LLM request [{}]: modelName={}, messages={}, tools={}, options={}",
                label,
                safe(delegate.getModelName()),
                summarizeMessages(messages),
                summarizeTools(tools),
                summarizeOptions(options));

        StringBuilder textAgg = new StringBuilder();
        List<String> otherBlocks = new ArrayList<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();

        return delegate.stream(messages, tools, options)
                .doOnNext(chunk -> {
                    if (chunk == null) {
                        return;
                    }
                    if (chunk.getFinishReason() != null && !chunk.getFinishReason().isBlank()) {
                        finishReason.set(chunk.getFinishReason());
                    }
                    if (chunk.getUsage() != null) {
                        usage.set(chunk.getUsage());
                    }
                    List<ContentBlock> content = chunk.getContent();
                    if (content == null) {
                        return;
                    }
                    for (ContentBlock block : content) {
                        if (block instanceof TextBlock textBlock) {
                            String t = textBlock.getText();
                            if (t != null) {
                                textAgg.append(t);
                            }
                        } else if (block != null) {
                            otherBlocks.add(summarizeBlock(block));
                        }
                    }
                })
                .doOnComplete(() -> log.debug(
                        "LLM response [{}]: content={}, finishReason={}, usage={}",
                        label,
                        buildAggregatedContent(textAgg, otherBlocks),
                        finishReason.get(),
                        summarizeUsage(usage.get())))
                .doOnError(err -> log.warn(
                        "LLM stream error [{}]: {}: {}",
                        label,
                        err.getClass().getSimpleName(),
                        err.getMessage()));
    }

    private static String buildAggregatedContent(StringBuilder textAgg, List<String> otherBlocks) {
        StringBuilder out = new StringBuilder();
        if (!textAgg.isEmpty()) {
            out.append("text=").append(textAgg);
        }
        if (!otherBlocks.isEmpty()) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append("blocks=").append(otherBlocks);
        }
        return out.isEmpty() ? "[]" : out.toString();
    }

    private static String summarizeMessages(List<Msg> messages) {
        if (messages == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            if (msg == null) {
                parts.add("null");
                continue;
            }
            parts.add("{role=" + msg.getRole() + ", content=" + summarizeContent(msg.getContent()) + "}");
        }
        return parts.toString();
    }

    private static String summarizeContent(List<ContentBlock> content) {
        if (content == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>(content.size());
        for (ContentBlock block : content) {
            parts.add(summarizeBlock(block));
        }
        return parts.toString();
    }

    private static String summarizeBlock(ContentBlock block) {
        if (block == null) {
            return "null";
        }
        if (block instanceof TextBlock textBlock) {
            return "text(" + textBlock.getText() + ")";
        }
        if (block instanceof ToolUseBlock toolUse) {
            return "tool_use(id=" + toolUse.getId()
                    + ", name=" + toolUse.getName()
                    + ", input=" + toolUse.getInput() + ")";
        }
        if (block instanceof ToolResultBlock toolResult) {
            return "tool_result(id=" + toolResult.getId()
                    + ", name=" + toolResult.getName()
                    + ", output=" + summarizeContent(toolResult.getOutput()) + ")";
        }
        return block.getClass().getSimpleName();
    }

    private static String summarizeTools(List<ToolSchema> tools) {
        if (tools == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>(tools.size());
        for (ToolSchema tool : tools) {
            if (tool == null) {
                parts.add("null");
                continue;
            }
            parts.add("{name=" + tool.getName()
                    + ", description=" + tool.getDescription()
                    + ", parameters=" + tool.getParameters() + "}");
        }
        return parts.toString();
    }

    private static String summarizeOptions(GenerateOptions options) {
        if (options == null) {
            return "null";
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("modelName", options.getModelName());
        safe.put("stream", options.getStream());
        safe.put("temperature", options.getTemperature());
        safe.put("topP", options.getTopP());
        safe.put("maxTokens", options.getMaxTokens());
        safe.put("maxCompletionTokens", options.getMaxCompletionTokens());
        safe.put("toolChoice", options.getToolChoice());
        safe.put("parallelToolCalls", options.getParallelToolCalls());
        safe.put("thinkingBudget", options.getThinkingBudget());
        safe.put("reasoningEffort", options.getReasoningEffort());
        safe.put("additionalHeaders", redactHeaders(options.getAdditionalHeaders()));
        return safe.toString();
    }

    private static Map<String, String> redactHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("authorization")
                    || lower.contains("api-key")
                    || lower.contains("apikey")
                    || lower.contains("token")) {
                out.put(e.getKey(), "***");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static String summarizeUsage(ChatUsage usage) {
        if (usage == null) {
            return "null";
        }
        return "{input=" + usage.getInputTokens()
                + ", output=" + usage.getOutputTokens()
                + ", total=" + usage.getTotalTokens() + "}";
    }

    private static String safe(String value) {
        return value == null ? "null" : value;
    }
}
