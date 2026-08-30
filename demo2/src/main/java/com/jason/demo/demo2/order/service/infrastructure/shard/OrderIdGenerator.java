package com.jason.demo.demo2.order.service.infrastructure.shard;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import org.springframework.stereotype.Component;

/**
 * 下单专用发号：雪花 + 基因。{@code itemId} 仍走 {@link SnowflakeIdGenerator#nextId()}，不做基因。
 *
 * <p>雪花序号原 12 bit，被基因占 9 bit 后每毫秒每节点只剩 8 个号；覆盖低位后同一毫秒连续发号会撞，
 * 所以 {@link #nextOrderId} 必须串行，撞了就再取雪花。
 */
@Component
public class OrderIdGenerator {

    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private long lastId;

    public OrderIdGenerator(SnowflakeIdGenerator snowflakeIdGenerator) {
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * 低 9 位被基因覆盖后，同一毫秒内序号只差低位会撞号；撞了就再取雪花。
     */
    public synchronized long nextOrderId(long memberId) {
        long id;
        do {
            id = OrderShardGene.embed(snowflakeIdGenerator.nextId(), memberId);
        } while (id == lastId);
        lastId = id;
        return id;
    }
}
