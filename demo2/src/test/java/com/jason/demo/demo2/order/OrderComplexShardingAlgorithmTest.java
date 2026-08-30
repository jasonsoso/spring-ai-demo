package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.infrastructure.shard.OrderComplexShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderComplexShardingAlgorithmTest {

    private final OrderComplexShardingAlgorithm algorithm = new OrderComplexShardingAlgorithm();

    @Test
    void memberIdOnly_routesDbAndTable() {
        assertEquals(List.of("order_ds_0"), algorithm.doSharding(List.of("order_ds_0", "order_ds_1"),
                value("demo_order", "member_id", 612L)));
        assertEquals(List.of("demo_order_18"), algorithm.doSharding(orderTables(),
                value("demo_order", "member_id", 612L)));
        assertEquals(List.of("demo_order_item_18"), algorithm.doSharding(itemTables(),
                value("demo_order_item", "member_id", 612L)));
    }

    @Test
    void orderIdOnly_extractsGene() {
        long orderId = (99L << 9) | 100L;
        assertEquals(List.of("order_ds_0"), algorithm.doSharding(List.of("order_ds_0", "order_ds_1"),
                value("demo_order", "order_id", orderId)));
        assertEquals(List.of("demo_order_18"), algorithm.doSharding(orderTables(),
                value("demo_order", "order_id", orderId)));
    }

    @Test
    void bothPresent_usesMemberId() {
        long mismatchedOrderId = (99L << 9) | 7L;
        assertEquals(List.of("demo_order_18"), algorithm.doSharding(orderTables(),
                both("demo_order", 612L, mismatchedOrderId)));
    }

    @Test
    void neitherColumn_forbidsBroadcast() {
        ComplexKeysShardingValue<Comparable<?>> empty = new ComplexKeysShardingValue<>(
                "demo_order", Map.of(), Map.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> algorithm.doSharding(orderTables(), empty));
        assertTrue(ex.getMessage().contains("broadcast"));
    }

    private static ComplexKeysShardingValue<Comparable<?>> value(String logic, String column, long id) {
        Map<String, Collection<Comparable<?>>> cols = new HashMap<>();
        cols.put(column, List.of(id));
        return new ComplexKeysShardingValue<>(logic, cols, Map.of());
    }

    private static ComplexKeysShardingValue<Comparable<?>> both(String logic, long memberId, long orderId) {
        Map<String, Collection<Comparable<?>>> cols = new HashMap<>();
        cols.put("member_id", List.of(memberId));
        cols.put("order_id", List.of(orderId));
        return new ComplexKeysShardingValue<>(logic, cols, Map.of());
    }

    private static List<String> orderTables() {
        return IntStream.range(0, 32).mapToObj(i -> "demo_order_" + i).toList();
    }

    private static List<String> itemTables() {
        return IntStream.range(0, 32).mapToObj(i -> "demo_order_item_" + i).toList();
    }
}
