package com.jason.demo.demo2.product.app.listener;

import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEventPublisher;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 出箱 → RocketMQ。只负责发消息，禁止调 applyDelta。
 * sendNow 成功才 XACK；失败留在 PEL，由 claim 补发。
 */
@Slf4j
@Component
public class RedisStockOutboxRelay implements SmartLifecycle {

    private static final Duration CLAIM_MIN_IDLE = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final StockSyncEventPublisher publisher;
    private final ProductStockProperties properties;

    private volatile boolean running;
    private Thread worker;
    private int loopCount;

    public RedisStockOutboxRelay(
            StringRedisTemplate redis,
            StockSyncEventPublisher publisher,
            ProductStockProperties properties) {
        this.redis = redis;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.isRedisHotEnabled();
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        ensureGroup();
        worker = Thread.ofVirtual().name("stock-outbox-relay").start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void onRecord(Map<String, String> fields, String recordId) {
        StockSyncEvent event = new StockSyncEvent(
                Long.parseLong(required(fields, "productId")),
                Long.parseLong(required(fields, "orderId")),
                required(fields, "optType"),
                Integer.parseInt(required(fields, "qty")),
                required(fields, "idempotentKey"),
                Long.parseLong(required(fields, "seq")));
        publisher.sendNow(event);
        // 必须先 send 再 ACK：抛错则本条仍 pending，避免 Redis 已成功、MySQL 永远收不到
        redis.opsForStream().acknowledge(RedisStockKeys.OUTBOX, properties.getOutboxGroup(), recordId);
    }

    public void claimIdlePending() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        PendingMessages pending = ops.pending(RedisStockKeys.OUTBOX, properties.getOutboxGroup(), Range.unbounded(), 100L);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<RecordId> idleIds = new ArrayList<>();
        for (PendingMessage message : pending) {
            Duration idle = message.getElapsedTimeSinceLastDelivery();
            if (idle != null && idle.compareTo(CLAIM_MIN_IDLE) >= 0) {
                idleIds.add(message.getId());
            }
        }
        if (idleIds.isEmpty()) {
            return;
        }
        List<MapRecord<String, Object, Object>> claimed = ops.claim(
                RedisStockKeys.OUTBOX,
                properties.getOutboxGroup(),
                properties.getOutboxConsumer(),
                CLAIM_MIN_IDLE,
                idleIds.toArray(RecordId[]::new));
        if (claimed == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : claimed) {
            onRecord(stringify(record.getValue()), record.getId().getValue());
        }
    }

    private void loop() {
        while (running) {
            try {
                loopCount++;
                readOnce();
                if (loopCount % 10 == 0) {
                    // 发送失败的条目不会再被 lastConsumed 读到，靠 min-idle claim 重投
                    claimIdlePending();
                }
            } catch (RuntimeException ex) {
                if (!running) {
                    return;
                }
                log.warn("stock outbox relay loop error", ex);
            }
        }
    }

    private void readOnce() {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(properties.getOutboxGroup(), properties.getOutboxConsumer()),
                StreamReadOptions.empty()
                        .count(properties.getOutboxBatchSize())
                        .block(Duration.ofMillis(properties.getOutboxBlockMs())),
                StreamOffset.create(RedisStockKeys.OUTBOX, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            onRecord(stringify(record.getValue()), record.getId().getValue());
        }
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(
                    RedisStockKeys.OUTBOX, ReadOffset.from("0-0"), properties.getOutboxGroup());
        } catch (RuntimeException ignored) {
            // BUSYGROUP：组已存在
        }
    }

    private static Map<String, String> stringify(Map<Object, Object> value) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (value == null) {
            return fields;
        }
        value.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        return fields;
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing stream field: " + name);
        }
        return value;
    }
}
