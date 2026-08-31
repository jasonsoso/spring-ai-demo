package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.infrastructure.shard.OrderIdGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发生号压测：默认关闭，需显式开启。
 * <pre>
 * mvn -Dtest=OrderIdGeneratorConcurrencyStressTest -DorderId.stress=true test
 * # 可选：-DorderId.stress.durationMs=300000 -DorderId.stress.threads=16
 * </pre>
 */
@Tag("stress")
@EnabledIfSystemProperty(named = "orderId.stress", matches = "true")
class OrderIdGeneratorConcurrencyStressTest {

    @Test
    void concurrentNextOrderId_noDuplicatesWithinDuration() throws Exception {
        long durationMs = Long.getLong("orderId.stress.durationMs", TimeUnit.MINUTES.toMillis(5));
        int threads = Integer.getInteger("orderId.stress.threads", 16);

        OrderIdGenerator generator = new OrderIdGenerator(7L, System::currentTimeMillis);
        ConcurrentHashMap.KeySetView<Long, Boolean> unique = ConcurrentHashMap.newKeySet(1 << 20);
        AtomicLong generated = new AtomicLong();
        AtomicLong duplicates = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);

        CountDownLatch started = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long wallStart = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            pool.execute(() -> {
                started.countDown();
                long memberBase = threadIndex * 10_000L;
                long localSeq = 0L;
                while (running.get()) {
                    long memberId = memberBase + (localSeq++ % 10_000L);
                    long id = generator.nextOrderId(memberId);
                    generated.incrementAndGet();
                    if (!unique.add(id)) {
                        duplicates.incrementAndGet();
                    }
                }
            });
        }

        assertTrue(started.await(10, TimeUnit.SECONDS), "workers failed to start");

        long deadline = wallStart + durationMs;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(30_000L);
            long elapsed = System.currentTimeMillis() - wallStart;
            long count = generated.get();
            double rate = count * 1000.0 / Math.max(1L, elapsed);
            System.out.printf(
                    "progress: elapsed=%ds generated=%d unique=%d dup=%d rate=%.0f/s%n",
                    elapsed / 1000L, count, unique.size(), duplicates.get(), rate);
        }
        running.set(false);
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not stop");

        long elapsed = System.currentTimeMillis() - wallStart;
        long total = generated.get();
        long uniqueCount = unique.size();
        long dup = duplicates.get();
        double rate = total * 1000.0 / Math.max(1L, elapsed);

        System.out.printf(
                "RESULT: duration=%dms threads=%d generated=%d unique=%d duplicates=%d rate=%.0f/s%n",
                elapsed, threads, total, uniqueCount, dup, rate);

        assertEquals(0L, dup, "found duplicate order ids");
        assertEquals(total, uniqueCount, "unique size must equal generated count");
        assertTrue(total > 0L, "should generate at least one id");
    }
}
