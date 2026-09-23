package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderShardGeneTest {

    @Test
    void virtual612_routesToDs1Table30() {
        long virtual = OrderShardGene.virtualOfMember(612L);
        assertEquals(61L, virtual);
        assertEquals(100L, OrderShardGene.virtualOfOrderId((55L << 9) | 100L));
        assertEquals(1, OrderShardGene.dsIndex(virtual));
        assertEquals(30, OrderShardGene.tableIndex(virtual));
        assertEquals("000111101", OrderShardGene.geneBits(virtual));
        assertEquals("order_ds_1", OrderShardGene.dsName(virtual));
        assertEquals("demo_order_30", OrderShardGene.orderTableName(virtual));
        assertEquals("demo_order_item_30", OrderShardGene.itemTableName(virtual));
    }

    @Test
    void boundaries_zeroAnd511() {
        assertEquals(252L, OrderShardGene.virtualOfMember(0L));
        assertEquals(0, OrderShardGene.dsIndex(0L));
        assertEquals(0, OrderShardGene.tableIndex(0L));
        assertEquals(60L, OrderShardGene.virtualOfMember(511L));
        assertEquals(1, OrderShardGene.dsIndex(511L));
        assertEquals(255 % 32, OrderShardGene.tableIndex(511L));
        assertEquals("111111111", OrderShardGene.geneBits(511L));
    }

    @Test
    void snowflakeZeroSequence_plusOneMillisChangesSlot() {
        long first = 0x1D090DD4C4000000L;
        long second = 0x1D090E2966800000L;
        long plusOneMillis = first + (1L << 22);
        assertEquals(44L, OrderShardGene.virtualOfMember(first));
        assertEquals("order_ds_0", OrderShardGene.dsName(OrderShardGene.virtualOfMember(first)));
        assertEquals("demo_order_22", OrderShardGene.orderTableName(OrderShardGene.virtualOfMember(first)));
        assertEquals(23L, OrderShardGene.virtualOfMember(second));
        assertEquals("order_ds_1", OrderShardGene.dsName(OrderShardGene.virtualOfMember(second)));
        assertEquals("demo_order_11", OrderShardGene.orderTableName(OrderShardGene.virtualOfMember(second)));
        assertEquals(487L, OrderShardGene.virtualOfMember(plusOneMillis));
        assertNotEquals(OrderShardGene.virtualOfMember(first), OrderShardGene.virtualOfMember(plusOneMillis));
    }

    @Test
    void bothDatabasesUseAll32Tables() {
        Set<String> ds0 = new HashSet<>();
        Set<String> ds1 = new HashSet<>();
        for (long memberId = 0; memberId < 512; memberId++) {
            long v = OrderShardGene.virtualOfMember(memberId);
            if (OrderShardGene.dsIndex(v) == 0) {
                ds0.add(OrderShardGene.orderTableName(v));
            } else {
                ds1.add(OrderShardGene.orderTableName(v));
            }
        }
        assertEquals(32, ds0.size());
        assertEquals(32, ds1.size());
        for (int i = 0; i < 32; i++) {
            assertTrue(ds0.contains("demo_order_" + i));
            assertTrue(ds1.contains("demo_order_" + i));
        }
    }

    @Test
    void wrongModulo32_wouldLeaveOddTablesEmptyOnOneDs() {
        Set<Integer> ds0Wrong = new HashSet<>();
        for (long memberId = 0; memberId < 512; memberId++) {
            long v = memberId % 512;
            if ((v % 2) == 0) {
                ds0Wrong.add((int) (v % 32));
            }
        }
        assertNotEquals(32, ds0Wrong.size());
    }
}
