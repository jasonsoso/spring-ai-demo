package com.jason.demo.demo2.agentscope.middleware;

import com.jason.demo.demo2.agentscope.observability.AgentExecutionContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class AgentExecutionLoggingMiddleware implements MiddlewareBase {

    private static final Logger log =
            LoggerFactory.getLogger(AgentExecutionLoggingMiddleware.class);

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentExecutionContext ids = AgentExecutionContext.from(context);
            long startedAt = System.nanoTime();
            AtomicInteger answerChars = new AtomicInteger();
            log.info(
                    "Agent execution started. requestId={}, traceId={}, spanId={}, "
                            + "agent={}, userId={}, sessionId={}",
                    ids.requestId(),
                    ids.traceId(),
                    ids.spanId(),
                    agent.getName(),
                    context.getUserId(),
                    context.getSessionId());
            return Flux.defer(() -> next.apply(input))
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta
                                && delta.getDelta() != null) {
                            answerChars.addAndGet(delta.getDelta().length());
                        }
                    })
                    .doOnComplete(() -> log.info(
                            "Agent execution completed. requestId={}, traceId={}, "
                                    + "spanId={}, durationMs={}, answerChars={}, state=SUCCESS",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            elapsedMillis(startedAt),
                            answerChars.get()))
                    .doOnError(error -> log.warn(
                            "Agent execution failed. requestId={}, traceId={}, spanId={}, "
                                    + "durationMs={}, errorType={}, state=ERROR",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            elapsedMillis(startedAt),
                            error.getClass().getSimpleName()))
                    .doOnCancel(() -> log.warn(
                            "Agent execution cancelled. requestId={}, traceId={}, spanId={}, "
                                    + "durationMs={}, state=CANCELLED",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            elapsedMillis(startedAt)));
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentExecutionContext ids = AgentExecutionContext.from(context);
            int round = AgentExecutionContext.nextReasoningRound(context);
            long startedAt = System.nanoTime();
            log.info(
                    "Reasoning started. requestId={}, traceId={}, spanId={}, "
                            + "round={}, messageCount={}, toolCount={}",
                    ids.requestId(),
                    ids.traceId(),
                    ids.spanId(),
                    round,
                    size(input.messages()),
                    size(input.tools()));
            return Flux.defer(() -> next.apply(input))
                    .doOnComplete(() -> log.info(
                            "Reasoning completed. requestId={}, traceId={}, spanId={}, "
                                    + "round={}, durationMs={}, state=SUCCESS",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            round,
                            elapsedMillis(startedAt)))
                    .doOnError(error -> log.warn(
                            "Reasoning failed. requestId={}, traceId={}, spanId={}, "
                                    + "round={}, durationMs={}, errorType={}, state=ERROR",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            round,
                            elapsedMillis(startedAt),
                            error.getClass().getSimpleName()))
                    .doOnCancel(() -> log.warn(
                            "Reasoning cancelled. requestId={}, traceId={}, spanId={}, "
                                    + "round={}, durationMs={}, state=CANCELLED",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            round,
                            elapsedMillis(startedAt)));
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentExecutionContext ids = AgentExecutionContext.from(context);
            long startedAt = System.nanoTime();
            AtomicReference<ChatUsage> completedUsage = new AtomicReference<>();
            String model = modelName(input);
            log.info(
                    "Model call started. requestId={}, traceId={}, spanId={}, model={}",
                    ids.requestId(),
                    ids.traceId(),
                    ids.spanId(),
                    model);
            return Flux.defer(() -> next.apply(input))
                    .doOnNext(event -> {
                        if (event instanceof ModelCallEndEvent endEvent) {
                            completedUsage.set(endEvent.getUsage());
                        }
                    })
                    .doOnComplete(() -> {
                        ChatUsage usage = completedUsage.get();
                        log.info(
                                "Model call completed. requestId={}, traceId={}, spanId={}, "
                                        + "model={}, durationMs={}, inputTokens={}, "
                                        + "outputTokens={}, state=SUCCESS",
                                ids.requestId(),
                                ids.traceId(),
                                ids.spanId(),
                                model,
                                elapsedMillis(startedAt),
                                inputTokens(usage),
                                outputTokens(usage));
                    })
                    .doOnError(error -> log.warn(
                            "Model call failed. requestId={}, traceId={}, spanId={}, "
                                    + "model={}, durationMs={}, errorType={}, state=ERROR",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            model,
                            elapsedMillis(startedAt),
                            error.getClass().getSimpleName()))
                    .doOnCancel(() -> log.warn(
                            "Model call cancelled. requestId={}, traceId={}, spanId={}, "
                                    + "model={}, durationMs={}, state=CANCELLED",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            model,
                            elapsedMillis(startedAt)));
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentExecutionContext ids = AgentExecutionContext.from(context);
            long startedAt = System.nanoTime();
            List<ToolUseBlock> toolCalls =
                    input.toolCalls() == null ? List.of() : input.toolCalls();
            log.info(
                    "Tool execution started. requestId={}, traceId={}, spanId={}, toolCount={}",
                    ids.requestId(),
                    ids.traceId(),
                    ids.spanId(),
                    toolCalls.size());
            for (ToolUseBlock toolCall : toolCalls) {
                if (toolCall != null) {
                    log.info(
                            "Tool execution planned. requestId={}, traceId={}, spanId={}, "
                                    + "tool={}, toolCallId={}",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            toolCall.getName(),
                            toolCall.getId());
                }
            }
            return Flux.defer(() -> next.apply(input))
                    .doOnNext(event -> {
                        if (event instanceof ToolResultEndEvent result) {
                            log.info(
                                    "Tool execution result. requestId={}, traceId={}, spanId={}, "
                                            + "tool={}, toolCallId={}, state={}",
                                    ids.requestId(),
                                    ids.traceId(),
                                    ids.spanId(),
                                    result.getToolCallName(),
                                    result.getToolCallId(),
                                    result.getState());
                        }
                    })
                    .doOnComplete(() -> log.info(
                            "Tool execution completed. requestId={}, traceId={}, spanId={}, "
                                    + "durationMs={}, state=SUCCESS",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            elapsedMillis(startedAt)))
                    .doOnError(error -> log.warn(
                            "Tool execution failed. requestId={}, traceId={}, spanId={}, "
                                    + "durationMs={}, errorType={}, state=ERROR",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            elapsedMillis(startedAt),
                            error.getClass().getSimpleName()))
                    .doOnCancel(() -> log.warn(
                            "Tool execution cancelled. requestId={}, traceId={}, spanId={}, "
                                    + "durationMs={}, state=CANCELLED",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            elapsedMillis(startedAt)));
        });
    }

    @Override
    public Mono<String> onSystemPrompt(
            Agent agent, RuntimeContext context, String systemPrompt) {
        AgentExecutionContext ids = AgentExecutionContext.from(context);
        log.debug(
                "System prompt observed. requestId={}, traceId={}, spanId={}, promptChars={}",
                ids.requestId(),
                ids.traceId(),
                ids.spanId(),
                systemPrompt == null ? 0 : systemPrompt.length());
        return Mono.justOrEmpty(systemPrompt);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static String modelName(ModelCallInput input) {
        return input.model() == null ? "-" : input.model().getModelName();
    }

    private static Object inputTokens(ChatUsage usage) {
        return usage == null ? "-" : usage.getInputTokens();
    }

    private static Object outputTokens(ChatUsage usage) {
        return usage == null ? "-" : usage.getOutputTokens();
    }
}
