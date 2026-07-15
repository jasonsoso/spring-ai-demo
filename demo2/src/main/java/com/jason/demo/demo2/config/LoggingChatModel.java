package com.jason.demo.demo2.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 在 ChatModel 层打印 prompt / response，覆盖不经过 ChatClient Advisor 的调用
 *（例如 Embabel SpringAiLlmMessageSender）。
 */
public final class LoggingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModel.class);

    private final ChatModel delegate;
    private final String label;

    public LoggingChatModel(ChatModel delegate, String label) {
        this.delegate = delegate;
        this.label = label == null || label.isBlank() ? "chat-model" : label;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (log.isDebugEnabled()) {
            log.debug("LLM request [{}]: {}", label, prompt);
        }
        ChatResponse response = delegate.call(prompt);
        if (log.isDebugEnabled()) {
            log.debug("LLM response [{}]: {}", label, response);
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (log.isDebugEnabled()) {
            log.debug("LLM stream request [{}]: {}", label, prompt);
        }
        return delegate.stream(prompt)
                .doOnNext(chunk -> {
                    if (log.isDebugEnabled()) {
                        log.debug("LLM stream chunk [{}]: {}", label, chunk);
                    }
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }
}
