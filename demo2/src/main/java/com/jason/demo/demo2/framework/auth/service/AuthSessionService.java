package com.jason.demo.demo2.framework.auth.service;

import com.jason.demo.demo2.framework.auth.configuration.AuthProperties;
import com.jason.demo.demo2.framework.auth.model.AuthSession;
import com.jason.demo.demo2.framework.auth.web.AuthHttpSupport;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuthSessionService {

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final AuthProperties properties;

    public AuthSessionService(StringRedisTemplate redis, JsonMapper jsonMapper, AuthProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    public AuthSession createSession(Long memberId, String phone, String avatarUrl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthSession session = new AuthSession(
                token,
                memberId,
                phone,
                avatarUrl,
                LocalDateTime.now(),
                properties.getSessionTtl().toSeconds());
        String key = buildSessionKey(token);
        String json = toJson(session);
        // 不用 opsForValue().set(key, value, Expiration)：部分 Boot4/Lettuce 下
        // DefaultedRedisConnection#set 会无限递归（StackOverflowError），改走 Lua（见 SnowflakeNodeAllocator）。
        DefaultRedisScript<String> script = new DefaultRedisScript<>(
                "return redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))",
                String.class);
        redis.execute(script, List.of(key), json, String.valueOf(properties.getSessionTtl().toSeconds()));
        return session;
    }

    public AuthSession requireSession(String token) {
        String raw = redis.opsForValue().get(buildSessionKey(token));
        if (raw == null || raw.isBlank()) {
            throw AuthHttpSupport.unauthorized();
        }
        AuthSession stored = fromJson(raw);
        return new AuthSession(
                token,
                stored.memberId(),
                stored.phone(),
                stored.avatarUrl(),
                stored.loginAt(),
                properties.getSessionTtl().toSeconds());
    }

    public boolean deleteSession(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.delete(buildSessionKey(token)));
    }

    public String buildSessionKey(String token) {
        return properties.getSessionKeyPrefix() + token;
    }

    private String toJson(AuthSession session) {
        try {
            return jsonMapper.writeValueAsString(session);
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to serialize auth session", e);
        }
    }

    private AuthSession fromJson(String raw) {
        try {
            return jsonMapper.readValue(raw, AuthSession.class);
        } catch (JacksonException e) {
            throw AuthHttpSupport.unauthorized();
        }
    }
}
