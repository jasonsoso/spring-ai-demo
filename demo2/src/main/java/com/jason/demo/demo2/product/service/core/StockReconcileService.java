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
 * 对账先比 seq。Redis 超前是在途，即使 avail 对不上也不报 AVAIL_MISMATCH；
 * 齐了才比 avail ≟ mysql.stock。
 */
@Slf4j
@Service
public class StockReconcileService {

    private final RedisStockOps redisStockOps;
    private final ProductStockRepository productStockRepository;
    private final ProductStockProperties properties;
    private final Clock clock;
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

    public ReconcileKindEnum reconcileOne(long productId) {
        ProductStock mysql = productStockRepository.requireByProductId(productId);
        long mysqlSeq = mysql.getStockSeq() == null ? 0L : mysql.getStockSeq();
        Optional<Long> redisSeqOpt = redisStockOps.getSeq(productId);
        if (redisSeqOpt.isEmpty()) {
            lagStartedAt.remove(productId);
            log.info("stock reconcile redis missing, productId={}", productId);
            return ReconcileKindEnum.REDIS_MISSING;
        }
        long redisSeq = redisSeqOpt.get();
        if (redisSeq > mysqlSeq) {
            // 同一 product 连续落后才计时，seq 对齐后 map 会 remove
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
            log.warn("stock reconcile mysql ahead, productId={}, redisSeq={}, mysqlSeq={}",
                    productId, redisSeq, mysqlSeq);
            return ReconcileKindEnum.MYSQL_AHEAD;
        }
        Optional<Long> avail = redisStockOps.getAvail(productId);
        if (avail.isEmpty() || avail.get().intValue() != mysql.getStock()) {
            log.warn("stock reconcile avail mismatch, productId={}, redisAvail={}, mysqlStock={}",
                    productId, avail.orElse(null), mysql.getStock());
            return ReconcileKindEnum.AVAIL_MISMATCH;
        }
        return ReconcileKindEnum.OK;
    }
}
