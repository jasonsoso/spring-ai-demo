package com.jason.demo.demo2.order.service.infrastructure.shard;

import com.jason.demo.demo2.framework.id.SnowflakeNodeAllocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 订单号位图发号：{@code [41 时间][5 机器][8 序号][9 基因]}。
 * 不调用 {@link com.jason.demo.demo2.framework.id.SnowflakeIdGenerator}；{@code itemId} 仍走雪花。
 */
@Component
public class OrderIdGenerator {

    static final long EPOCH = 1288834974657L;

    private static final int WORKER_BITS = 5;
    private static final int SEQ_BITS = 8;
    private static final int GENE_BITS = OrderShardGene.GENE_BITS;
    private static final int WORKER_SHIFT = SEQ_BITS + GENE_BITS;
    private static final int TIMESTAMP_SHIFT = WORKER_BITS + WORKER_SHIFT;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1L;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1L;

    private final long workerId;
    private final LongSupplier wallClockMs;

    private long lastTimestamp = -1L;
    private long sequence;

    @Autowired
    public OrderIdGenerator(SnowflakeNodeAllocator allocator) {
        this(allocator.current().workerId(), System::currentTimeMillis);
    }

    /** 单测：固定 worker + 可控时钟。 */
    public OrderIdGenerator(long workerId, LongSupplier wallClockMs) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId out of range: " + workerId);
        }
        this.workerId = workerId;
        this.wallClockMs = wallClockMs;
    }

    public synchronized long nextOrderId(long memberId) {
        long gene = OrderShardGene.virtualOfMember(memberId);
        long now = wallClockMs.getAsLong();
        if (now < lastTimestamp) {
            throw new IllegalStateException(
                    "clock moved backward, last=" + lastTimestamp + ", now=" + now);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQ_MASK;
            if (sequence == 0L) {
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | (sequence << GENE_BITS)
                | gene;
    }

    private long waitNextMillis(long last) {
        long now = wallClockMs.getAsLong();
        while (now <= last) {
            now = wallClockMs.getAsLong();
        }
        return now;
    }
}
