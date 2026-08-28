package com.jason.demo.demo2.product.service.infrastructure.redis;

import com.jason.demo.demo2.product.service.common.RedisStockResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Redis 热库存操作。Hash 仅 avail+seq；预占票与出箱由 Lua 原子完成。
 * 读票用 GET+DEL，不用 GETDEL，兼容 Redis &lt; 6.2。
 */
@Component
public class RedisStockOps {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<List> reserveScript;
    private final DefaultRedisScript<List> confirmScript;
    private final DefaultRedisScript<List> releaseScript;
    private final DefaultRedisScript<List> adjustScript;

    public RedisStockOps(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.reserveScript = load("lua/stock-reserve.lua");
        this.confirmScript = load("lua/stock-confirm.lua");
        this.releaseScript = load("lua/stock-release.lua");
        this.adjustScript = load("lua/stock-adjust.lua");
    }

    public RedisStockResult reserve(long productId, long orderId, int qty, String idempotentKey) {
        return eval(reserveScript, hotKeys(productId, orderId),
                String.valueOf(qty), String.valueOf(orderId), String.valueOf(productId), idempotentKey);
    }

    public RedisStockResult confirm(long productId, long orderId, String idempotentKey) {
        return eval(confirmScript, hotKeys(productId, orderId),
                String.valueOf(orderId), String.valueOf(productId), idempotentKey);
    }

    public RedisStockResult release(long productId, long orderId, String idempotentKey) {
        return eval(releaseScript, hotKeys(productId, orderId),
                String.valueOf(orderId), String.valueOf(productId), idempotentKey);
    }

    /** 运营 ADJUST 成功后覆盖 Hash，使后续热路径 seq 从 MySQL 对齐点继续 +1。 */
    public RedisStockResult adjustHash(long productId, int avail, long seq) {
        return eval(adjustScript, List.of(RedisStockKeys.hash(productId)),
                String.valueOf(avail), String.valueOf(seq));
    }

    /**
     * 上架灌入：仅当 Hash 不存在时写入。已存在必须返回 false 且不要再 HSET avail，
     * 否则会用 mysql.stock 盖掉热路径已扣减的可售。
     */
    public boolean hsetnxHash(long productId, int avail, long seq) {
        Boolean created = stringRedisTemplate.opsForHash()
                .putIfAbsent(RedisStockKeys.hash(productId), "avail", String.valueOf(avail));
        if (Boolean.TRUE.equals(created)) {
            stringRedisTemplate.opsForHash().put(RedisStockKeys.hash(productId), "seq", String.valueOf(seq));
            return true;
        }
        return false;
    }

    public Optional<Long> getAvail(long productId) {
        return getLongField(productId, "avail");
    }

    public Optional<Long> getSeq(long productId) {
        return getLongField(productId, "seq");
    }

    private Optional<Long> getLongField(long productId, String field) {
        Object raw = stringRedisTemplate.opsForHash().get(RedisStockKeys.hash(productId), field);
        if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(String.valueOf(raw).trim()));
    }

    private static List<String> hotKeys(long productId, long orderId) {
        return List.of(
                RedisStockKeys.hash(productId),
                RedisStockKeys.ticket(orderId, productId),
                RedisStockKeys.OUTBOX);
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> load(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(List.class);
        return script;
    }

    @SuppressWarnings("unchecked")
    private RedisStockResult eval(DefaultRedisScript<List> script, List<String> keys, String... argv) {
        List<Object> raw = stringRedisTemplate.execute(script, keys, (Object[]) argv);
        if (raw == null || raw.size() < 2) {
            throw new IllegalStateException("unexpected lua result: " + raw);
        }
        int code = Integer.parseInt(String.valueOf(raw.get(0)));
        return new RedisStockResult(code, String.valueOf(raw.get(1)));
    }
}
