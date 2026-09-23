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

/**
 * ShardingSphere CLASS_BASED 复合算法。由 SS 反射创建，无 Spring 注入，只调 {@link OrderShardGene}。
 *
 * <p>优先级：有 {@code member_id} 用会员；只有 {@code order_id} 拆基因；两者都没有则抛错（禁止广播 64 张表）。
 * 库策略与表策略共用本类，各调一次：{@code availableTargetNames} 分别是 {@code order_ds_*} 或 {@code demo_order_*}。
 *
 * <p>入参只有逻辑表名和分片键，没有 SQL 原文。SQL 原文由 {@code sql-show} 打到 logger {@code ShardingSphere-SQL}。
 */
@Slf4j
public class OrderComplexShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    @Override
    public void init(Properties props) {
        // CLASS_BASED 会调 TypedSPI.init；公式写死在 OrderShardGene，这里不要读配置
    }

    @Override
    public Collection<String> doSharding(
            Collection<String> availableTargetNames,
            ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        // 只认等值 / IN。范围条件（>、BETWEEN）不在这个 map 里，会落到下面的禁止广播。
        Collection<Long> memberIds = longs(shardingValue, "member_id", "memberId");
        Collection<Long> orderIds = longs(shardingValue, "order_id", "orderId");
        if (memberIds.isEmpty() && orderIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "order shard requires member_id or order_id, broadcast forbidden");
        }
        // 会员优先。两边都有时忽略 order_id：基因和会员不一致就落到会员那张表，查不到由业务返回 404。
        Set<String> result = new LinkedHashSet<>();
        String source = memberIds.isEmpty() ? "order_id" : "member_id";
        Collection<Long> values = memberIds.isEmpty() ? orderIds : memberIds;
        boolean fromOrderId = memberIds.isEmpty();
        // IN 多个 id 会得到多个目标。库策略、表策略各进一次本方法。
        for (Long id : values) {
            // 会员：MurmurHash3 后取余；只有订单号：取低 9 位基因。同一个 virtual 再拆库和表。
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

    /** 只给日志展示用。真正选中的库或表在 {@link #pickTarget}。 */
    private static String logicTableName(String logic, long virtual) {
        if (logic != null && logic.contains("item")) {
            return OrderShardGene.itemTableName(virtual);
        }
        return OrderShardGene.orderTableName(virtual);
    }

    /**
     * 同一次路由先选库、再选表，两次进来的 {@code available} 不同。
     * 候选名以 {@code order_ds_} 开头就是在选库，否则按逻辑表名决定主表还是明细表。
     */
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

    /** 库候选是 {@code order_ds_0/1}，表候选是 {@code demo_order_*} / {@code demo_order_item_*}。 */
    private static boolean isDatabaseTargets(Collection<String> available) {
        return available.stream().anyMatch(n -> n.startsWith("order_ds_"));
    }

    /**
     * 从等值 / IN 里取出指定列。SS 传来的列名可能是 {@code member_id} 或 {@code memberId}，去掉下划线再比。
     */
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
