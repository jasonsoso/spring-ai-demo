package com.jason.demo.demo2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolReasoningAgentServiceTest {

    private ChatMemory chatMemory;
    private ToolReasoningAgentService service;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        service = new ToolReasoningAgentService(chatMemory);
    }

    @Test
    void validateSessionId_acceptsAlphanumeric() {
        assertDoesNotThrow(() -> service.validateSessionId("session_001"));
    }

    @Test
    void validateSessionId_rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.validateSessionId("../evil"));
        assertThrows(IllegalArgumentException.class, () -> service.validateSessionId("bad id"));
        assertThrows(IllegalArgumentException.class, () -> service.validateSessionId(null));
    }

    @Test
    void clearSession_delegatesToChatMemory() {
        service.clearSession("session_001");
        verify(chatMemory).clear("session_001");
    }
}
