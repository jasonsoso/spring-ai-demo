package com.jason.demo.demo2.product.service.common;

/**
 * 热库存对账分流，由 {@code StockReconcileService.reconcileOne} 返回。
 *
 * <p>只打日志、不改库存。{@link #IN_FLIGHT} 是投影在途，不是故障；
 * {@link #AVAIL_MISMATCH} 仅在 Redis/MySQL seq 已齐时才有意义。
 */
public enum ReconcileKindEnum {

    /** seq 齐且 {@code redis.avail == mysql.stock}。 */
    OK,

    /** Redis seq 超前：Lua 已改热库存，MQ 尚未投影到 MySQL。 */
    IN_FLIGHT,

    /** 同一 sku 连续 {@link #IN_FLIGHT} 超过 {@code reconcileLagAlarmMs}，投影卡住。 */
    IN_FLIGHT_SLOW,

    /** seq 已齐但可售对不上；seq 未齐时即使 avail 不同也不走这里。 */
    AVAIL_MISMATCH,

    /** MySQL seq 超前：Redis 被覆盖/丢 key，或冷路径只写了账本。 */
    MYSQL_AHEAD,

    /** MySQL 有库存行但 Redis Hash 无 seq（未灌入或已过期）。 */
    REDIS_MISSING
}
