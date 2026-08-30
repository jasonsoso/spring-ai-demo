package com.jason.demo.demo2.order.service.infrastructure.shard;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import org.springframework.stereotype.Component;

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
