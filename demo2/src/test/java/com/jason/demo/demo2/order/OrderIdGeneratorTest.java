package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.infrastructure.shard.OrderIdGenerator;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderIdGeneratorTest {

    private static final long EPOCH = 1288834974657L;

    @Test
    void nextOrderId_low9BitsMatchMemberVirtual() {
        AtomicLong now = new AtomicLong(EPOCH + 1_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(1L, now::get);
        long first = gen.nextOrderId(612L);
        long second = gen.nextOrderId(612L);
        assertEquals(100L, OrderShardGene.virtualOfOrderId(first));
        assertEquals(100L, OrderShardGene.virtualOfOrderId(second));
        assertNotEquals(first, second);
    }

    @Test
    void sameMillis_sequencesDifferInSeqBits() {
        AtomicLong now = new AtomicLong(EPOCH + 2_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(3L, now::get);
        long a = gen.nextOrderId(612L);
        long b = gen.nextOrderId(612L);
        assertEquals(now.get(), (a >> 22) + EPOCH);
        assertEquals(0L, (a >> 9) & 0xFFL);
        assertEquals(1L, (b >> 9) & 0xFFL);
        assertEquals(3L, (a >> 17) & 0x1FL);
    }

    @Test
    void differentWorkers_sameMillisSeqGene_differ() {
        long ts = EPOCH + 3_000_000L;
        OrderIdGenerator w1 = new OrderIdGenerator(1L, () -> ts);
        OrderIdGenerator w2 = new OrderIdGenerator(2L, () -> ts);
        long a = w1.nextOrderId(612L);
        long b = w2.nextOrderId(612L);
        assertEquals(100L, OrderShardGene.virtualOfOrderId(a));
        assertEquals(100L, OrderShardGene.virtualOfOrderId(b));
        assertNotEquals(a, b);
        assertEquals(1L, (a >> 17) & 0x1FL);
        assertEquals(2L, (b >> 17) & 0x1FL);
    }

    @Test
    void sequenceOverflow_waitsNextMillis() throws Exception {
        AtomicLong now = new AtomicLong(EPOCH + 4_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(1L, now::get);
        for (int i = 0; i < 256; i++) {
            gen.nextOrderId(1L);
        }
        Thread advancer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now.incrementAndGet();
        });
        advancer.start();
        long id = gen.nextOrderId(1L);
        advancer.join(2000);
        assertEquals(EPOCH + 4_000_001L, (id >> 22) + EPOCH);
        assertEquals(0L, (id >> 9) & 0xFFL);
        assertEquals(1L, OrderShardGene.virtualOfOrderId(id));
    }

    @Test
    void clockMovedBackward_throws() {
        AtomicLong now = new AtomicLong(EPOCH + 5_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(1L, now::get);
        gen.nextOrderId(1L);
        now.set(EPOCH + 4_000_000L);
        assertThrows(IllegalStateException.class, () -> gen.nextOrderId(1L));
    }

    @Test
    void manyIds_allUnique() {
        AtomicLong tick = new AtomicLong(0);
        OrderIdGenerator gen = new OrderIdGenerator(7L, () -> EPOCH + 6_000_000L + tick.getAndIncrement() / 200);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(gen.nextOrderId(612L)));
        }
        assertEquals(1000, ids.size());
    }
}
