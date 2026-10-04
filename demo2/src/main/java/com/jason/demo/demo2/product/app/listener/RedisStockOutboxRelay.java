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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis Stream 出箱 Relay：读 {@code demo2:stock:outbox}，发 RocketMQ，再 XACKDEL。
 * <p>
 * 只投递，禁止调 {@code applyDelta}（MySQL 投影由 {@link StockSyncMqListener} 做）。
 * 新消息走 {@link StreamMessageListenerContainer}；卡住的 PEL 由独立 {@code pending+claim} 补发。
 * 多实例共用同一消费组、各自唯一 consumerName，避免 PEL 被抢乱。
 */
@Slf4j
@Component
public class RedisStockOutboxRelay implements SmartLifecycle,
        StreamListener<String, MapRecord<String, String, String>> {

    /** XACKDEL ACKED：本组 ACK 后，仅当所有组都已确认才删 Stream 正文。需要 Redis 8.2+。 */
    private static final RedisStreamCommands.XDelOptions ACKED = RedisStreamCommands.XDelOptions
            .deletionPolicy(RedisStreamCommands.StreamDeletionPolicy.ACKNOWLEDGED);

    private final StringRedisTemplate redis;
    private final StockSyncEventPublisher publisher;
    private final ProductStockProperties properties;
    /** {@code {prefix}-{hostname}-{pid}}，同组多机互不覆盖 PEL。 */
    private final String consumerName;
    /** 容器 poll 线程；看门狗用 {@link #pollFuture} 判断这条任务是否已死。 */
    private ExecutorService pollExecutor;

    /** 串行化 start/stop/watchOnce，避免停机时还在重注册。 */
    private final Object lifecycle = new Object();
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    private volatile Future<?> pollFuture;
    private ScheduledExecutorService scheduler;
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
        this.pollExecutor = newPollExecutor();
    }

    @Override
    public boolean isAutoStartup() {
        return properties.isRedisHotEnabled();
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            // 先建组再 mark running：组失败时不要留下半启动状态
            ensureGroup();
            if (pollExecutor == null || pollExecutor.isShutdown()) {
                pollExecutor = newPollExecutor();
            }
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
            if (scheduler == null) {
                scheduler = Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "stock-outbox-scheduler");
                    t.setDaemon(true);
                    return t;
                });
            }
            // 容器只读 '>'；PEL 超时条目必须另开 pending+claim（SDR 4.1 无 autoClaim）
            long interval = properties.getOutboxClaimIntervalMs();
            scheduler.scheduleWithFixedDelay(() -> runScheduled("claim", () -> {
                if (running) {
                    claimIdlePending();
                }
            }), interval, interval, TimeUnit.MILLISECONDS);
            long watchdog = properties.getOutboxWatchdogIntervalMs();
            scheduler.scheduleWithFixedDelay(
                    () -> runScheduled("watchdog", this::watchOnce),
                    watchdog, watchdog, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycle) {
            // 先清 running，避免 watchdog 在拆容器时又 register
            running = false;
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            if (subscription != null && container != null) {
                container.remove(subscription);
                subscription = null;
            }
            if (container != null) {
                container.stop();
            }
            if (pollExecutor != null) {
                pollExecutor.shutdownNow();
            }
        }
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

    /**
     * 投递成功才 ACK。{@code sendNow} 抛错则条目仍在 PEL，由 claim 补发，
     * 避免 Redis 已删、RocketMQ/MySQL 永远收不到。
     */
    public void onRecord(Map<String, String> fields, String recordId) {
        StockSyncEvent event = new StockSyncEvent(
                Long.parseLong(required(fields, "productId")),
                Long.parseLong(required(fields, "orderId")),
                required(fields, "optType"),
                Integer.parseInt(required(fields, "qty")),
                required(fields, "idempotentKey"),
                Long.parseLong(required(fields, "seq")));
        publisher.sendNow(event);
        List<StreamEntryDeletionResult> results = redis.opsForStream().acknowledgeAndDelete(
                RedisStockKeys.OUTBOX, properties.getOutboxGroup(), ACKED, recordId);
        StreamEntryDeletionResult result = results == null || results.isEmpty() ? null : results.get(0);
        // ACKED 下 NOT_FOUND / NOT_ACKED 不抛：可能已被别的实例删，重试只会打转
        if (result != StreamEntryDeletionResult.DELETED) {
            log.warn("outbox entry not deleted after ack, recordId={}, result={}", recordId, result);
        }
    }

    /**
     * 等价于 XAUTOCLAIM：列出空闲超过 minIdle 的 PEL，claim 到本 consumer 再走 {@link #onRecord}。
     * 单条失败只记日志，不中断本批，让周期任务下次再捞。
     */
    public void claimIdlePending() {
        Duration minIdle = Duration.ofMillis(properties.getOutboxClaimMinIdleMs());
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        PendingMessages pending = ops.pending(
                RedisStockKeys.OUTBOX,
                properties.getOutboxGroup(),
                Range.unbounded(),
                properties.getOutboxBatchSize(),
                minIdle);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<RecordId> ids = new ArrayList<>();
        for (PendingMessage message : pending) {
            ids.add(message.getId());
        }
        if (ids.isEmpty()) {
            return;
        }
        List<MapRecord<String, Object, Object>> claimed = ops.claim(
                RedisStockKeys.OUTBOX,
                properties.getOutboxGroup(),
                consumerName,
                minIdle,
                ids.toArray(RecordId[]::new));
        if (claimed == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : claimed) {
            try {
                onRecord(stringify(record.getValue()), record.getId().getValue());
            } catch (RuntimeException ex) {
                log.warn("stock outbox claim send failed, recordId={}", record.getId(), ex);
            }
        }
    }

    /** 订阅没了、inactive，或 poll Future 已结束，说明容器读循环挂了，需要同名重注册。 */
    public boolean shouldReregister(Subscription sub, Future<?> poll) {
        if (sub == null) {
            return true;
        }
        if (!sub.isActive()) {
            return true;
        }
        return poll != null && poll.isDone();
    }

    /**
     * 看门狗：容器默认 cancelOnError=true 会停订阅。
     * 同名重注册（不 DELCONSUMER），PEL 仍归本进程，由 claim 消化。
     */
    public void watchOnce() {
        if (!running) {
            return;
        }
        if (container == null) {
            return;
        }
        if (!shouldReregister(subscription, pollFuture)) {
            return;
        }
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            if (subscription != null) {
                container.remove(subscription);
            }
            subscription = container.register(newReadRequest(), this);
            if (!container.isRunning()) {
                container.start();
            }
        }
    }

    public String consumerName() {
        return consumerName;
    }

    public ExecutorService pollExecutor() {
        return pollExecutor;
    }

    /**
     * 覆盖 SDR 默认：手动 ACK；{@code cancelOnError=false} 避免 {@code onMessage} 一抛就拆订阅。
     * {@code lastConsumed()} 即 XREADGROUP {@code >}，只拉从未投递给本组的新条目。
     */
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
            // 组已存在是常态（多实例 / 重启），不能当启动失败
            if (isBusyGroup(ex)) {
                return;
            }
            throw ex;
        }
    }

    /** 把容器 poll 提交到单线程池，并记下 Future，给看门狗探测「读循环是否已退出」。 */
    Executor trackingExecutor() {
        return command -> pollFuture = pollExecutor.submit(command);
    }

    /**
     * ScheduledThreadPoolExecutor 默认「任务抛错即取消后续周期」。
     * claim/watchdog 必须自己吞异常，否则补发和重注册会永久停掉。
     */
    private static void runScheduled(String task, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("stock outbox {} scheduled task failed", task, ex);
        } catch (Exception ex) {
            log.warn("stock outbox {} scheduled task failed", task, ex);
        }
    }

    private static ExecutorService newPollExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stock-outbox-poll");
            t.setDaemon(true);
            return t;
        });
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
