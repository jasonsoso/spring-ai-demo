package com.jason.demo.demo2.order.service.infrastructure.redis;

import com.jason.demo.demo2.order.config.OrderProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 预览/下单 Redis：preview payload、place 锁、幂等 result。
 * SET+TTL 必须走 Lua；Boot4 {@code opsForValue().set(..., Expiration)} 会 StackOverflow。
 */
@Service
public class OrderPlaceTokenStore {

    private static final DefaultRedisScript<String> SET_EX = new DefaultRedisScript<>(
            "return redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))",
            String.class);
    private static final DefaultRedisScript<String> SET_NX_EX = new DefaultRedisScript<>(
            "return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', tonumber(ARGV[2]))",
            String.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final OrderProperties properties;

    public OrderPlaceTokenStore(StringRedisTemplate redis, JsonMapper jsonMapper, OrderProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    public void savePreview(String token, OrderPlaceTokenPayload payload, Duration ttl) {
        String json = toJson(payload);
        redis.execute(SET_EX, List.of(OrderPlaceTokenKeys.preview(token)), json, String.valueOf(ttl.toSeconds()));
    }

    public Optional<OrderPlaceTokenPayload> getPreview(String token) {
        String raw = redis.opsForValue().get(OrderPlaceTokenKeys.preview(token));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(raw, OrderPlaceTokenPayload.class));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    public boolean tryLock(String token, Duration lease) {
        String result = redis.execute(
                SET_NX_EX,
                List.of(OrderPlaceTokenKeys.lock(token)),
                token,
                String.valueOf(lease.toSeconds()));
        return "OK".equals(result);
    }

    public void unlock(String token) {
        redis.delete(OrderPlaceTokenKeys.lock(token));
    }

    public Optional<Long> getResult(String token) {
        String raw = redis.opsForValue().get(OrderPlaceTokenKeys.result(token));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public void saveResult(String token, long orderId, Duration ttl) {
        redis.execute(
                SET_EX,
                List.of(OrderPlaceTokenKeys.result(token)),
                String.valueOf(orderId),
                String.valueOf(ttl.toSeconds()));
    }

    private String toJson(OrderPlaceTokenPayload payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to serialize order place token payload", e);
        }
    }
}
