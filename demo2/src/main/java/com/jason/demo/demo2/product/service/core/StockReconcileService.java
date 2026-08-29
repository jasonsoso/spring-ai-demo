package com.jason.demo.demo2.product.service.core;

import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.common.ReconcileKindEnum;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热库存对账：先比 seq，再比 avail。
 *
 * <p>热路径是 Lua 先改 Redis，MySQL 由 MQ 按 seq 投影，所以 Redis 超前是常态在途，
 * 此时 avail 本来就会暂时对不上，不能报 {@link ReconcileKindEnum#AVAIL_MISMATCH}。
 * seq 对齐后才比 {@code redis.avail ≟ mysql.stock}。
 *
 * <p>由 {@link com.jason.demo.demo2.product.app.job.StockReconcileJob} 定时扫全表调用。
 */
@Slf4j
@Service
public class StockReconcileService {

    private final RedisStockOps redisStockOps;
    private final ProductStockRepository productStockRepository;
    private final ProductStockProperties properties;
    /** 生产用系统钟；单测注入 {@link Clock#fixed} 才能把落后窗口拨过告警阈值。 */
    private final Clock clock;
    /**
     * 每个 sku 最近一次开始「Redis seq 超前」的时刻。
     * 对齐或 Redis 缺失则 remove，避免间歇落后被累加成 {@link ReconcileKindEnum#IN_FLIGHT_SLOW}。
     */
    private final ConcurrentHashMap<Long, Instant> lagStartedAt = new ConcurrentHashMap<>();

    @Autowired
    public StockReconcileService(
            RedisStockOps redisStockOps,
            ProductStockRepository productStockRepository,
            ProductStockProperties properties) {
        this(redisStockOps, productStockRepository, properties, Clock.systemDefaultZone());
    }

    public StockReconcileService(
            RedisStockOps redisStockOps,
            ProductStockRepository productStockRepository,
            ProductStockProperties properties,
            Clock clock) {
        this.redisStockOps = redisStockOps;
        this.productStockRepository = productStockRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 对一个 sku 做一次分流，只打日志不改库存。
     *
     * <p>缺失 seq → {@code REDIS_MISSING}；Redis 超前 → {@code IN_FLIGHT}（过久则 {@code IN_FLIGHT_SLOW}）；
     * MySQL 超前 → {@code MYSQL_AHEAD}；seq 齐了才比 avail，对不上才是 {@code AVAIL_MISMATCH}。
     */
    public ReconcileKindEnum reconcileOne(long productId) {
        ProductStock mysql = productStockRepository.requireByProductId(productId);
        long mysqlSeq = mysql.getStockSeq() == null ? 0L : mysql.getStockSeq();
        Optional<Long> redisSeqOpt = redisStockOps.getSeq(productId);
        if (redisSeqOpt.isEmpty()) {
            // Hash 未灌或已过期；没有 seq 就谈不上在途计时
            lagStartedAt.remove(productId);
            log.info("stock reconcile redis missing, productId={}", productId);
            return ReconcileKindEnum.REDIS_MISSING;
        }
        long redisSeq = redisSeqOpt.get();
        if (redisSeq > mysqlSeq) {
            // MQ 投影尚未追上 Redis；同一 sku 连续落后才计时，中间对齐过会重新起算
            Instant started = lagStartedAt.computeIfAbsent(productId, id -> clock.instant());
            Duration lag = Duration.between(started, clock.instant());
            if (lag.toMillis() >= properties.getReconcileLagAlarmMs()) {
                log.warn("stock reconcile in-flight too slow, productId={}, redisSeq={}, mysqlSeq={}, lagMs={}",
                        productId, redisSeq, mysqlSeq, lag.toMillis());
                return ReconcileKindEnum.IN_FLIGHT_SLOW;
            }
            log.debug("stock reconcile in-flight, productId={}, redisSeq={}, mysqlSeq={}",
                    productId, redisSeq, mysqlSeq);
            return ReconcileKindEnum.IN_FLIGHT;
        }
        lagStartedAt.remove(productId);
        if (redisSeq < mysqlSeq) {
            // Redis 被覆盖/丢 key 后重灌失败，或冷路径只写了 MySQL
            log.warn("stock reconcile mysql ahead, productId={}, redisSeq={}, mysqlSeq={}",
                    productId, redisSeq, mysqlSeq);
            return ReconcileKindEnum.MYSQL_AHEAD;
        }
        // seq 已齐：可售应对上账本；缺 avail 字段也当不一致
        Optional<Long> avail = redisStockOps.getAvail(productId);
        if (avail.isEmpty() || avail.get().intValue() != mysql.getStock()) {
            log.warn("stock reconcile avail mismatch, productId={}, redisAvail={}, mysqlStock={}",
                    productId, avail.orElse(null), mysql.getStock());
            return ReconcileKindEnum.AVAIL_MISMATCH;
        }
        return ReconcileKindEnum.OK;
    }
}
