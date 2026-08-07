package com.jason.demo.demo2.framework.id;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SnowflakeNodeAllocator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeNodeAllocator.class);
    private static final int MAX_NODE_ID = 31;

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

    private static final String LUA_RENEW = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return 0
            """;

    private static final String LUA_RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
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

    public long ensureDatacenterId(String appName) {
        String dcKey = properties.getKeyPrefix() + ":dc:" + appName;
        String usedKey = properties.getKeyPrefix() + ":dc:used";
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(LUA_ENSURE_DC);
        script.setResultType(String.class);
        String result = redis.execute(script, List.of(dcKey, usedKey));
        if (result == null || result.isBlank() || "false".equalsIgnoreCase(result)) {
            throw new IllegalStateException(
                    "Snowflake datacenterId slots exhausted (0-31) for prefix="
                            + properties.getKeyPrefix());
        }
        return Long.parseLong(result);
    }

    public AllocatedSnowflakeNode allocate() {
        long dc = ensureDatacenterId(applicationName);
        String instanceId = UUID.randomUUID().toString();
        Long workerId = null;
        for (int i = 0; i <= MAX_NODE_ID; i++) {
            String key = workerKey(dc, i);
            Boolean ok = redis.opsForValue().setIfAbsent(
                    key, instanceId, Duration.ofSeconds(properties.getLeaseTtlSeconds()));
            if (Boolean.TRUE.equals(ok)) {
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

    public AllocatedSnowflakeNode current() {
        if (node == null) {
            throw new IllegalStateException("Snowflake node not allocated");
        }
        return node;
    }

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
