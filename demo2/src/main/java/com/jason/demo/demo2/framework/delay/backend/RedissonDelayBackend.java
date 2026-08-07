package com.jason.demo.demo2.framework.delay.backend;

import com.jason.demo.demo2.framework.delay.DelayTaskExecutor;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redisson 延时后端：{@link RDelayedQueue} 到期转入阻塞队列，后台单线程消费并调用 {@link DelayTaskExecutor}。
 */
@Slf4j
@Component
public class RedissonDelayBackend implements DelayBackend {

    public static final String NAME = "redisson";

    private final DelayProperties properties;
    private final DelayTaskExecutor executor;
    private final RDelayedQueue<Long> delayedQueue;
    private final RBlockingQueue<Long> destinationQueue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService consumer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "redisson-delay-consumer");
        t.setDaemon(true);
        return t;
    });

    public RedissonDelayBackend(
            RedissonClient redissonClient,
            DelayProperties properties,
            DelayTaskExecutor executor) {
        this.properties = properties;
        this.executor = executor;
        this.destinationQueue = redissonClient.getBlockingQueue(properties.getRedissonQueueName());
        this.delayedQueue = redissonClient.getDelayedQueue(destinationQueue);
        consumer.submit(this::consumeLoop);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void schedule(long taskId, Duration delay) {
        long millis = Math.max(1L, delay.toMillis());
        delayedQueue.offer(taskId, millis, TimeUnit.MILLISECONDS);
        log.info("redisson delay scheduled, taskId={}, delayMs={}", taskId, millis);
    }

    @Override
    public void cancel(long taskId) {
        boolean removed = delayedQueue.remove(taskId);
        log.info("redisson delay cancel, taskId={}, removed={}", taskId, removed);
    }

    /** 阻塞拉取到期 taskId 并执行；中断或关闭时退出。 */
    private void consumeLoop() {
        while (running.get()) {
            try {
                Long taskId = destinationQueue.poll(1, TimeUnit.SECONDS);
                if (taskId != null) {
                    executor.execute(taskId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("redisson delay consumer error", e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        consumer.shutdownNow();
        try {
            delayedQueue.destroy();
        } catch (Exception e) {
            log.warn("destroy redisson delayed queue failed", e);
        }
    }
}
