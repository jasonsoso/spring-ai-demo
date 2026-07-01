package com.jason.demo.demo2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionMemoryTripAgentServiceTest {

    private SessionService sessionService;
    private SessionMemoryTripAgentService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        service = new SessionMemoryTripAgentService(
                null, sessionService, null, null, null, null, "deepseek-v4-pro");
    }

    @Test
    void validateUserId_acceptsAlphanumeric() {
        assertDoesNotThrow(() -> service.validateUserId("user_1001"));
    }

    @Test
    void validateUserId_rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.validateUserId("../evil"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUserId("user 999"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUserId(null));
    }

    @Test
    void listEvents_whenSessionMissing_returnsEmptyStats() {
        when(sessionService.findById("1001")).thenReturn(null);

        Map<String, Object> result = service.listEvents("1001");

        assertEquals("1001", result.get("userId"));
        assertEquals(0, result.get("totalEvents"));
        assertEquals(List.of(), result.get("events"));
    }

    @Test
    void clearSession_delegatesToSessionServiceWhenExists() {
        Session session = Session.builder().id("1001").userId("1001").build();
        when(sessionService.findById("1001")).thenReturn(session);

        service.clearSession("1001");

        verify(sessionService).delete("1001");
    }

    @Test
    void clearSession_skipsDeleteWhenMissing() {
        when(sessionService.findById("1001")).thenReturn(null);

        service.clearSession("1001");

        verify(sessionService, never()).delete("1001");
    }
}
