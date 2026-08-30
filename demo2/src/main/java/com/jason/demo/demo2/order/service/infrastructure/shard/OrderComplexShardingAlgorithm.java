package com.jason.demo.demo2.order.service.infrastructure.shard;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Slf4j
public class OrderComplexShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    @Override
    public void init(Properties props) {
        // CLASS_BASED 会调 TypedSPI.init；无需配置项
    }

    @Override
    public Collection<String> doSharding(
            Collection<String> availableTargetNames,
            ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        Collection<Long> memberIds = longs(shardingValue, "member_id", "memberId");
        Collection<Long> orderIds = longs(shardingValue, "order_id", "orderId");
        if (memberIds.isEmpty() && orderIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "order shard requires member_id or order_id, broadcast forbidden");
        }
        Set<String> result = new LinkedHashSet<>();
        String source = memberIds.isEmpty() ? "order_id" : "member_id";
        Collection<Long> values = memberIds.isEmpty() ? orderIds : memberIds;
        boolean fromOrderId = memberIds.isEmpty();
        for (Long id : values) {
            long virtual = fromOrderId
                    ? OrderShardGene.virtualOfOrderId(id)
                    : OrderShardGene.virtualOfMember(id);
            String target = pickTarget(availableTargetNames, shardingValue.getLogicTableName(), virtual);
            result.add(target);
            log.info("order shard route, logic={}, virtual={}, ds={}, table={}, source={}",
                    shardingValue.getLogicTableName(),
                    virtual,
                    OrderShardGene.dsName(virtual),
                    logicTableName(shardingValue.getLogicTableName(), virtual),
                    source);
        }
        return new ArrayList<>(result);
    }

    private static String logicTableName(String logic, long virtual) {
        if (logic != null && logic.contains("item")) {
            return OrderShardGene.itemTableName(virtual);
        }
        return OrderShardGene.orderTableName(virtual);
    }

    private static String pickTarget(Collection<String> available, String logic, long virtual) {
        String expected;
        if (isDatabaseTargets(available)) {
            expected = OrderShardGene.dsName(virtual);
        } else if (logic != null && logic.contains("item")) {
            expected = OrderShardGene.itemTableName(virtual);
        } else {
            expected = OrderShardGene.orderTableName(virtual);
        }
        if (!available.contains(expected)) {
            throw new IllegalArgumentException("shard target not in available: " + expected);
        }
        return expected;
    }

    private static boolean isDatabaseTargets(Collection<String> available) {
        return available.stream().anyMatch(n -> n.startsWith("order_ds_"));
    }

    private static Collection<Long> longs(
            ComplexKeysShardingValue<Comparable<?>> value, String... columnNames) {
        Map<String, Collection<Comparable<?>>> map = value.getColumnNameAndShardingValuesMap();
        Set<Long> out = new LinkedHashSet<>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, Collection<Comparable<?>>> e : map.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
            for (String want : columnNames) {
                String normalized = want.toLowerCase(Locale.ROOT).replace("_", "");
                if (key.equals(normalized) && e.getValue() != null) {
                    for (Comparable<?> c : e.getValue()) {
                        if (c != null) {
                            out.add(((Number) c).longValue());
                        }
                    }
                }
            }
        }
        return out;
    }
}
