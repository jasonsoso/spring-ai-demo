package com.jason.demo.demo2.order.service.infrastructure.redis;

import com.jason.demo.demo2.order.service.infrastructure.config.OrderProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 预览/下单 Redis：preview payload、place 锁、幂等 result。
 * SET+TTL 必须走 Lua；Boot4 {@code opsForValue().set(..., Expiration)} 会 StackOverflow。
 * 下单互斥锁走 Redisson {@link RLock}，只允许持有线程解锁。
 */
@Service
public class OrderPlaceTokenStore {

    private static final DefaultRedisScript<String> SET_EX = new DefaultRedisScript<>(
            "return redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))",
            String.class);

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final JsonMapper jsonMapper;
    private final OrderProperties properties;

    public OrderPlaceTokenStore(
            StringRedisTemplate redis,
            RedissonClient redisson,
            JsonMapper jsonMapper,
            OrderProperties properties) {
        this.redis = redis;
        this.redisson = redisson;
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
        RLock lock = redisson.getLock(OrderPlaceTokenKeys.lock(token));
        try {
            return lock.tryLock(0, lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void unlock(String token) {
        RLock lock = redisson.getLock(OrderPlaceTokenKeys.lock(token));
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
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
