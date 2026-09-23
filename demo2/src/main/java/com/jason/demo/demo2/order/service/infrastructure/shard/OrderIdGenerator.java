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

    /** 与 Hutool 默认雪花同一起点（Twitter epoch），订单号量级接近其它雪花 ID。 */
    static final long EPOCH = 1288834974657L;

    /** 机器 5 bit，workerId 取值 0～31。数据中心不进订单号，原雪花那 5 bit 让给了序号侧。 */
    private static final int WORKER_BITS = 5;
    /** 同一毫秒、同一台机器共用一个序号，0～255，不按基因分开计数。 */
    private static final int SEQ_BITS = 8;
    private static final int GENE_BITS = OrderShardGene.GENE_BITS;
    /** 机器段在序号和基因之上：左移 8+9 = 17。 */
    private static final int WORKER_SHIFT = SEQ_BITS + GENE_BITS;
    /** 时间段在机器之上：左移 5+17 = 22。低位布局是 [序号 8][基因 9]。 */
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

    /**
     * 进程内串行发号。跨进程靠不同 workerId 隔离，不再「生成雪花再覆盖低 9 位」。
     * 低 9 位直接放入 {@code memberId % 512}，和序号分槽，避免盖掉序号后撞号。
     */
    public synchronized long nextOrderId(long memberId) {
        long gene = OrderShardGene.virtualOfMember(memberId);
        long now = wallClockMs.getAsLong();
        // 只拒绝回拨，不做 NTP 追赶。回拨时继续发号会和已经发出的号撞上。
        if (now < lastTimestamp) {
            throw new IllegalStateException(
                    "clock moved backward, last=" + lastTimestamp + ", now=" + now);
        }
        if (now == lastTimestamp) {
            // 同毫秒序号 +1，到 256 后掩码回到 0，说明这一毫秒的 256 个号用完了。
            sequence = (sequence + 1) & SEQ_MASK;
            if (sequence == 0L) {
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        // [41 时间][5 机器][8 序号][9 基因]，四段按位或，互不覆盖。
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | (sequence << GENE_BITS)
                | gene;
    }

    /** 序号用尽后空转到下一毫秒。调用方已持有 nextOrderId 的锁。 */
    private long waitNextMillis(long last) {
        long now = wallClockMs.getAsLong();
        while (now <= last) {
            now = wallClockMs.getAsLong();
        }
        return now;
    }
}
