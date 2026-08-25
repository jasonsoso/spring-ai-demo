package com.jason.demo.demo2.framework.auth;

import com.jason.demo.demo2.framework.auth.configuration.AuthProperties;
import com.jason.demo.demo2.framework.auth.model.AuthSession;
import com.jason.demo.demo2.framework.auth.service.AuthSessionService;
import com.jason.demo.demo2.framework.jackson.JacksonJsonCustomizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private AuthSessionService service;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AuthProperties properties = new AuthProperties();
        properties.setSessionKeyPrefix("demo2:auth:session:");
        properties.setSessionTtl(Duration.ofHours(24));
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonJsonCustomizer().longAndDateTimeJsonCustomizer().customize(builder);
        jsonMapper = builder.build();
        service = new AuthSessionService(redis, jsonMapper, properties);
    }

    @Test
    void createSessionStoresRedisValue() {
        AuthSession session = service.createSession(1001L, "13888999999", "https://example.com/a.png");

        assertEquals(1001L, session.memberId());
        assertEquals("13888999999", session.phone());
        assertEquals("https://example.com/a.png", session.avatarUrl());
        assertEquals(86400L, session.expiresInSeconds());
        verify(values).set(
                eq("demo2:auth:session:" + session.token()),
                anyString(),
                eq(Expiration.from(Duration.ofHours(24))));
    }

    @Test
    void requireSessionReadsRedisValue() {
        AuthSession stored = new AuthSession(
                null,
                1001L,
                "13888999999",
                "https://example.com/a.png",
                LocalDateTime.of(2026, 8, 24, 20, 0),
                0L);
        when(values.get("demo2:auth:session:t1")).thenReturn(jsonMapper.writeValueAsString(stored));

        AuthSession session = service.requireSession("t1");

        assertEquals("t1", session.token());
        assertEquals(1001L, session.memberId());
        assertEquals("13888999999", session.phone());
        assertEquals(86400L, session.expiresInSeconds());
    }

    @Test
    void requireSessionThrowsWhenMissing() {
        when(values.get("demo2:auth:session:gone")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.requireSession("gone"));

        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void deleteSessionDelegatesToRedis() {
        when(redis.delete("demo2:auth:session:t1")).thenReturn(true);

        assertTrue(service.deleteSession("t1"));
        verify(redis).delete("demo2:auth:session:t1");
    }
}
