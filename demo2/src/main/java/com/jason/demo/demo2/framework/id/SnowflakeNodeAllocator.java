package com.jason.demo.demo2.framework.id;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通过 Redis 自动分配 Snowflake 节点号（服务隔离模型）。
 * <ul>
 *   <li>{@code datacenterId}：代表服务，按 {@code spring.application.name} 永久绑定，范围 0~31</li>
 *   <li>{@code workerId}：代表本进程实例，带 TTL 租约，崩溃后靠过期回收，范围 0~31</li>
 * </ul>
 * Redis key（{@code {prefix} = app.snowflake.key-prefix}）：
 * <pre>
 *   {prefix}:dc:{appName}           → 该服务的 datacenterId（永久）
 *   {prefix}:dc:used                → 已占用的 datacenterId 集合
 *   {prefix}:worker:{dc}:{worker}   → worker 租约，value=instanceId，带 TTL
 * </pre>
 */
public class SnowflakeNodeAllocator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeNodeAllocator.class);
    /** Snowflake 默认 5 bit，合法范围 0~31 */
    private static final int MAX_NODE_ID = 31;

    /**
     * 永久绑定服务 → datacenterId。
     * KEYS[1]=dc 映射 key，KEYS[2]=used 集合；已存在则直接返回，否则原子占号。
     */
    private static final String LUA_ENSURE_DC = """
            local existing = redis.call('GET', KEYS[1])
            if existing then
              return existing
            end
            for i = 0, 31 do
              local id = tostring(i)
              if redis.call('SADD', KEYS[2], id) == 1 then
                redis.call('SET', KEYS[1], id)
                return id
              end
            end
            return false
            """;

    /** 仅当 value 仍是本实例 instanceId 时续约 TTL；被抢占则返回 0 */
    private static final String LUA_RENEW = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return 0
            """;

    /** 仅当仍持有租约时删除；避免误删其它实例的 key */
    private static final String LUA_RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /**
     * 抢占 worker：SET key instanceId NX EX ttl。
     * 不用 {@code setIfAbsent}：部分 Boot4/Lettuce 下 {@code DefaultedRedisConnection#set} 会无限递归。
     */
    private static final String LUA_TRY_ACQUIRE = """
            return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', tonumber(ARGV[2]))
            """;

    private final StringRedisTemplate redis;
    private final SnowflakeProperties properties;
    private final String applicationName;

    private AllocatedSnowflakeNode node;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private final AtomicBoolean heartbeatStopped = new AtomicBoolean(false);

    public SnowflakeNodeAllocator(
            StringRedisTemplate redis,
            SnowflakeProperties properties,
            String applicationName) {
        this.redis = redis;
        this.properties = properties;
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
    }

    /**
     * 为服务名获取（或首次分配）永久 datacenterId；槽位用尽则失败。
     * datacenterId 不自动回收，避免新服务复用旧号造成 ID 空间混淆。
     */
    public long ensureDatacenterId(String appName) {
        String dcKey = properties.getKeyPrefix() + ":dc:" + appName;
        String usedKey = properties.getKeyPrefix() + ":dc:used";
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(LUA_ENSURE_DC);
        script.setResultType(String.class);
        // 传占位 ARGV，保证 execute(script, keys, args...) 签名统一（Lua 忽略 ARGV）
        String result = redis.execute(script, List.of(dcKey, usedKey), "");
        if (result == null || result.isBlank() || "false".equalsIgnoreCase(result)) {
            throw new IllegalStateException(
                    "Snowflake datacenterId slots exhausted (0-31) for prefix="
                            + properties.getKeyPrefix());
        }
        return Long.parseLong(result);
    }

    /**
     * 启动时分配节点：先拿 datacenterId，再抢空闲 workerId。
     * Redis 不可用或 32 个 worker 都被占满 → 抛异常（fail-fast，不降级固定号）。
     */
    public AllocatedSnowflakeNode allocate() {
        long dc = ensureDatacenterId(applicationName);
        String instanceId = UUID.randomUUID().toString();
        Long workerId = null;
        for (int i = 0; i <= MAX_NODE_ID; i++) {
            if (tryAcquireWorker(workerKey(dc, i), instanceId)) {
                workerId = (long) i;
                break;
            }
        }
        if (workerId == null) {
            throw new IllegalStateException(
                    "Snowflake workerId slots exhausted for datacenterId=" + dc);
        }
        this.node = new AllocatedSnowflakeNode(applicationName, dc, workerId, instanceId);
        log.info("snowflake ready, app={}, datacenterId={}, workerId={}, instanceId={}",
                applicationName, dc, workerId, instanceId);
        return this.node;
    }

    /** @return true 表示抢到该 worker 租约 */
    private boolean tryAcquireWorker(String key, String instanceId) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(LUA_TRY_ACQUIRE);
        script.setResultType(String.class);
        String result = redis.execute(
                script,
                List.of(key),
                instanceId,
                String.valueOf(properties.getLeaseTtlSeconds()));
        return "OK".equalsIgnoreCase(result);
    }

    public AllocatedSnowflakeNode current() {
        if (node == null) {
            throw new IllegalStateException("Snowflake node not allocated");
        }
        return node;
    }

    /** 心跳续约；返回 false 表示租约已丢（可能被其它实例占用） */
    public boolean renewLease() {
        AllocatedSnowflakeNode n = current();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(LUA_RENEW);
        script.setResultType(Long.class);
        Long r = redis.execute(
                script,
                List.of(workerKey(n.datacenterId(), n.workerId())),
                n.instanceId(),
                String.valueOf(properties.getLeaseTtlSeconds()));
        return r != null && r > 0;
    }

    /** 优雅停机时释放租约；进程崩溃则依赖 TTL 自动过期 */
    public boolean releaseLease() {
        if (node == null) {
            return false;
        }
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(LUA_RELEASE);
        script.setResultType(Long.class);
        Long r = redis.execute(
                script,
                List.of(workerKey(node.datacenterId(), node.workerId())),
                node.instanceId());
        return r != null && r > 0;
    }

    /** 按 heartbeat-interval 周期续约；续约失败只打日志并停止心跳（首版不强制退出进程） */
    public void startHeartbeat() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snowflake-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long interval = properties.getHeartbeatIntervalSeconds();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (heartbeatStopped.get()) {
                return;
            }
            try {
                if (!renewLease()) {
                    log.error(
                            "snowflake lease renew failed (lost ownership?), app={}, datacenterId={}, workerId={}, instanceId={}",
                            node.applicationName(), node.datacenterId(), node.workerId(),
                            node.instanceId());
                    stopHeartbeat();
                }
            } catch (Exception e) {
                log.error("snowflake lease renew error, app={}", applicationName, e);
                stopHeartbeat();
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        heartbeatStopped.set(true);
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
    }

    /** Spring destroyMethod / AutoCloseable：停心跳并尽量释放 worker 租约 */
    @Override
    public void close() {
        stopHeartbeat();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            releaseLease();
        } catch (Exception e) {
            log.warn("snowflake lease release failed on shutdown", e);
        }
    }

    private String workerKey(long dc, long worker) {
        return properties.getKeyPrefix() + ":worker:" + dc + ":" + worker;
    }
}
