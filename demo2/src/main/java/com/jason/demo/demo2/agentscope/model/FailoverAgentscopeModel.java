package com.jason.demo.demo2.agentscope.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主模型按 maxAttempts 重试；仍在首 chunk 前失败且存在备用模型时切换。
 * 半路（已发出非 null chunk）失败不切备。
 */
public final class FailoverAgentscopeModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FailoverAgentscopeModel.class);

    private final Model primary;
    private final Model fallbackOrNull;
    private final int maxAttempts;
    private final AtomicReference<Model> active;

    public FailoverAgentscopeModel(Model primary, Model fallbackOrNull, int maxAttempts) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallbackOrNull = fallbackOrNull;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.active = new AtomicReference<>(primary);
    }

    @Override
    public String getModelName() {
        Model current = active.get();
        return current == null ? primary.getModelName() : current.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        active.set(primary);
        return tryModel(primary, maxAttempts, true, messages, tools, options);
    }

    private Flux<ChatResponse> tryModel(
            Model model,
            int remaining,
            boolean canSwitchToFallback,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            int attemptIndex = maxAttempts - remaining + 1;
            return model.stream(messages, tools, options)
                    .doOnNext(chunk -> {
                        if (chunk != null) {
                            emitted.set(true);
                        }
                    })
                    .onErrorResume(error -> {
                        if (emitted.get()) {
                            return Flux.error(error);
                        }
                        String modelName = safeName(model);
                        if (remaining > 1) {
                            log.info(
                                    "Model {} attempt {}/{} failed: {}",
                                    modelName,
                                    attemptIndex,
                                    maxAttempts,
                                    error.getClass().getSimpleName());
                            return tryModel(
                                    model,
                                    remaining - 1,
                                    canSwitchToFallback,
                                    messages,
                                    tools,
                                    options);
                        }
                        if (canSwitchToFallback && fallbackOrNull != null) {
                            log.info(
                                    "Primary model {} failed, switching to fallback {}",
                                    modelName,
                                    safeName(fallbackOrNull));
                            active.set(fallbackOrNull);
                            return tryModel(
                                    fallbackOrNull,
                                    maxAttempts,
                                    false,
                                    messages,
                                    tools,
                                    options);
                        }
                        log.warn(
                                "Model {} exhausted after {} attempts: {}",
                                modelName,
                                maxAttempts,
                                error.getClass().getSimpleName());
                        return Flux.error(error);
                    });
        });
    }

    private static String safeName(Model model) {
        try {
            String name = model.getModelName();
            return name == null || name.isBlank() ? "-" : name;
        } catch (RuntimeException ex) {
            return "-";
        }
    }
}
