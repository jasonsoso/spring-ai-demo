package com.jason.demo.demo2.product.app.listener;

import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEventPublisher;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamEntryDeletionResult;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Redis Stream 出箱 → RocketMQ。只负责发消息，禁止调 applyDelta。
 * sendNow 成功才 XACKDEL（ACKED）；失败留在 PEL，由 claim 补发。
 */
@Slf4j
@Component
public class RedisStockOutboxRelay implements SmartLifecycle,
        StreamListener<String, MapRecord<String, String, String>> {

    private static final Duration CLAIM_MIN_IDLE = Duration.ofSeconds(30);

    /** 本组确认后，仅当所有消费组都已确认才删正文。需要 Redis 8.2+ 的 XACKDEL。 */
    private static final RedisStreamCommands.XDelOptions ACKED = RedisStreamCommands.XDelOptions
            .deletionPolicy(RedisStreamCommands.StreamDeletionPolicy.ACKNOWLEDGED);

    private final StringRedisTemplate redis;
    private final StockSyncEventPublisher publisher;
    private final ProductStockProperties properties;
    private final String consumerName;
    private final ExecutorService pollExecutor;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    private volatile Future<?> pollFuture;
    private volatile boolean running;

    @Autowired
    public RedisStockOutboxRelay(
            StringRedisTemplate redis,
            StockSyncEventPublisher publisher,
            ProductStockProperties properties) {
        this(redis, publisher, properties, OutboxConsumerNames.forThisProcess(properties.getOutboxConsumer()));
    }

    public RedisStockOutboxRelay(
            StringRedisTemplate redis,
            StockSyncEventPublisher publisher,
            ProductStockProperties properties,
            String consumerName) {
        this.redis = redis;
        this.publisher = publisher;
        this.properties = properties;
        this.consumerName = consumerName;
        this.pollExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stock-outbox-poll");
            t.setDaemon(true);
            return t;
        });
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
        ensureGroup();
        if (container == null) {
            var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                    .pollTimeout(Duration.ofMillis(properties.getOutboxBlockMs()))
                    .batchSize(properties.getOutboxBatchSize())
                    .executor(trackingExecutor())
                    .serializer(RedisSerializer.string())
                    .build();
            container = StreamMessageListenerContainer.create(redis.getConnectionFactory(), options);
        }
        subscription = container.register(newReadRequest(), this);
        container.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (subscription != null && container != null) {
            container.remove(subscription);
            subscription = null;
        }
        if (container != null) {
            container.stop();
        }
        pollExecutor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> fields = new LinkedHashMap<>();
        message.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        onRecord(fields, message.getId().getValue());
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
        // 必须先 send 再 XACKDEL：抛错则本条仍 pending，避免 Redis 已成功、MySQL 永远收不到
        List<StreamEntryDeletionResult> results = redis.opsForStream().acknowledgeAndDelete(
                RedisStockKeys.OUTBOX, properties.getOutboxGroup(), ACKED, recordId);
        StreamEntryDeletionResult result = results == null || results.isEmpty() ? null : results.get(0);
        if (result != StreamEntryDeletionResult.DELETED) {
            log.warn("outbox entry not deleted after ack, recordId={}, result={}", recordId, result);
        }
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

    public String consumerName() {
        return consumerName;
    }

    public ExecutorService pollExecutor() {
        return pollExecutor;
    }

    public StreamMessageListenerContainer.StreamReadRequest<String> newReadRequest() {
        return StreamMessageListenerContainer.StreamReadRequest.builder(
                        StreamOffset.create(RedisStockKeys.OUTBOX, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(properties.getOutboxGroup(), consumerName))
                .autoAcknowledge(false)
                .cancelOnError(throwable -> false)
                .build();
    }

    public void ensureGroup() {
        try {
            redis.opsForStream().createGroup(
                    RedisStockKeys.OUTBOX, ReadOffset.from("0-0"), properties.getOutboxGroup());
        } catch (RuntimeException ex) {
            if (isBusyGroup(ex)) {
                return;
            }
            throw ex;
        }
    }

    Executor trackingExecutor() {
        return command -> pollFuture = pollExecutor.submit(command);
    }

    private static boolean isBusyGroup(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
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
