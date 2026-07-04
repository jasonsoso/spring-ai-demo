package com.jason.demo.demo2.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LkCoffeeAgentServiceTest {

    private final LkCoffeeAgentService service = new LkCoffeeAgentService(
            MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(20)
                    .build());

    @Test
    void validateSessionId_rejectsInvalid() {
        assertThatThrownBy(() -> service.validateSessionId("bad id!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveToken_prefersRequestToken() {
        assertThat(service.resolveToken("from-request", "from-env")).isEqualTo("from-request");
    }

    @Test
    void resolveToken_fallsBackToEnv() {
        assertThat(service.resolveToken(null, "from-env")).isEqualTo("from-env");
    }
}
