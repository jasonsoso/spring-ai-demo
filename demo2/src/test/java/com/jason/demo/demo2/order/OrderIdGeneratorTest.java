package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderIdGenerator;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrderIdGeneratorTest {

    @Test
    void nextOrderId_low9BitsMatchMemberVirtual() {
        OrderIdGenerator gen = new OrderIdGenerator(new SnowflakeIdGenerator(1, 1));
        long first = gen.nextOrderId(612L);
        long second = gen.nextOrderId(612L);
        assertEquals(100L, OrderShardGene.virtualOfOrderId(first));
        assertEquals(100L, OrderShardGene.virtualOfOrderId(second));
        assertNotEquals(first, second);
        assertNotEquals(first >> 9, second >> 9);
    }
}
