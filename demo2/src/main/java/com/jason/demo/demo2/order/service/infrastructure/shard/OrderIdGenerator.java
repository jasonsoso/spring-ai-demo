package com.jason.demo.demo2.order.service.infrastructure.shard;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import org.springframework.stereotype.Component;

@Component
public class OrderIdGenerator {

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public OrderIdGenerator(SnowflakeIdGenerator snowflakeIdGenerator) {
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    public long nextOrderId(long memberId) {
        return OrderShardGene.embed(snowflakeIdGenerator.nextId(), memberId);
    }
}
