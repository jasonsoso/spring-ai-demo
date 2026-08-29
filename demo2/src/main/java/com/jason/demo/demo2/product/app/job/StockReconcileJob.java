package com.jason.demo.demo2.product.app.job;

import com.baomidou.lock.annotation.Lock4j;
import com.jason.demo.demo2.lock.SkipIfLockedFailureStrategy;
import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.core.StockReconcileService;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 演示数据量小，全表扫描；热路径关闭则空转返回。多节点用 {@code @Lock4j} 互斥，抢不到锁跳过本轮。 */
@Slf4j
@Component
public class StockReconcileJob {

    private final ProductStockProperties properties;
    private final ProductStockRepository productStockRepository;
    private final StockReconcileService stockReconcileService;

    public StockReconcileJob(
            ProductStockProperties properties,
            ProductStockRepository productStockRepository,
            StockReconcileService stockReconcileService) {
        this.properties = properties;
        this.productStockRepository = productStockRepository;
        this.stockReconcileService = stockReconcileService;
    }

    @Scheduled(fixedDelayString = "${app.product.stock.reconcile-interval-ms:60000}")
    @Lock4j(
            keys = {"T(com.jason.demo.demo2.lock.LockKeys).stockReconcileKey()"},
            acquireTimeout = 0,
            expire = 60_000,
            failStrategy = SkipIfLockedFailureStrategy.class)
    public void run() {
        if (!properties.isRedisHotEnabled()) {
            return;
        }
        for (ProductStock row : productStockRepository.findAll()) {
            stockReconcileService.reconcileOne(row.getProductId());
        }
    }
}
