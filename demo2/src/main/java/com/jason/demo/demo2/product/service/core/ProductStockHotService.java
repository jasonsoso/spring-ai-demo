package com.jason.demo.demo2.product.service.core;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockIdempotentKeys;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.common.RedisStockResult;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockLogRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 热库存闸门：Lua 先改 Redis，MySQL 由 MQ 按 seq 投影。
 * Hash 不存在（UNLOADED）只返回 40010，热卖中禁止用 mysql.stock 灌 Redis。
 */
@Service
public class ProductStockHotService {

    private final ProductStockProperties properties;
    private final RedisStockOps redisStockOps;
    private final ProductStockDomainService domainService;
    private final ProductStockLogRepository stockLogRepository;
    private final ProductDomainService productDomainService;

    public ProductStockHotService(
            ProductStockProperties properties,
            RedisStockOps redisStockOps,
            ProductStockDomainService domainService,
            ProductStockLogRepository stockLogRepository,
            ProductDomainService productDomainService) {
        this.properties = properties;
        this.redisStockOps = redisStockOps;
        this.domainService = domainService;
        this.stockLogRepository = stockLogRepository;
        this.productDomainService = productDomainService;
    }

    public void reserve(long productId, long orderId, int qty) {
        if (qty <= 0) {
            throw new BusinessException(CommonErrorCodeEnum.BAD_REQUEST, "qty must be positive");
        }
        productDomainService.requireOnShelf(productId);
        if (!properties.isRedisHotEnabled()) {
            domainService.reserve(productId, orderId, qty);
            return;
        }
        RedisStockResult result = redisStockOps.reserve(
                productId, orderId, qty, idempotentKey(orderId, productId, ProductStockOptTypeEnum.RESERVE));
        switch (result.reason()) {
            case "OK", "IDEMPOTENT" -> {
            }
            // 未上架灌入过 Hash；不能在这里 HSET，否则会用过期 mysql.stock 覆盖热路径 avail
            case "UNLOADED" -> throw new BusinessException(ProductErrorCodeEnum.STOCK_SYNC_LAG);
            case "INSUFFICIENT" -> throw new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT);
            case "CONFLICT" -> throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
            default -> throw unexpected("reserve", result);
        }
    }

    public void confirm(long productId, long orderId, int qty) {
        if (!properties.isRedisHotEnabled()) {
            domainService.confirm(productId, orderId, qty);
            return;
        }
        RedisStockResult result = redisStockOps.confirm(
                productId, orderId, idempotentKey(orderId, productId, ProductStockOptTypeEnum.CONFIRM));
        switch (result.reason()) {
            case "OK", "IDEMPOTENT" -> {
            }
            case "UNLOADED" -> throw new BusinessException(ProductErrorCodeEnum.STOCK_SYNC_LAG);
            // 票已被 DEL：可能已支付、已取消，或投影尚未跟上，查 MySQL 流水表
            case "NOT_FOUND" -> inspectConfirmNotFound(orderId, productId);
            default -> throw unexpected("confirm", result);
        }
    }

    public void release(long productId, long orderId) {
        if (!properties.isRedisHotEnabled()) {
            domainService.release(productId, orderId);
            return;
        }
        RedisStockResult result = redisStockOps.release(
                productId, orderId, idempotentKey(orderId, productId, ProductStockOptTypeEnum.RELEASE));
        switch (result.reason()) {
            case "OK" -> {
            }
            case "UNLOADED" -> throw new BusinessException(ProductErrorCodeEnum.STOCK_SYNC_LAG);
            // Lua 没票不等于业务成功：可能已 CONFIRM，或 RESERVE 还在投影路上
            case "NO_TICKET" -> inspectReleaseNoTicket(orderId, productId);
            default -> throw unexpected("release", result);
        }
    }

    /** C 端只覆盖可售；empty 表示沿用 MySQL 映射，不要改 sellStock。 */
    public Optional<Integer> overlayAvail(long productId) {
        if (!properties.isRedisHotEnabled()) {
            return Optional.empty();
        }
        return redisStockOps.getAvail(productId).map(Math::toIntExact);
    }

    /** CONFIRM 无票：已确认则幂等；已释放则冲突；仅有预占则等投影；都没有则从未预占。 */
    private void inspectConfirmNotFound(long orderId, long productId) {
        if (stockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.CONFIRM)) {
            return;
        }
        if (stockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.RELEASE)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }
        if (stockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.RESERVE)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_SYNC_LAG);
        }
        throw new BusinessException(ProductErrorCodeEnum.RESERVE_LOG_NOT_FOUND);
    }

    /** RELEASE 无票：已释放则幂等成功；已确认则冲突；仅有预占则等投影；都没有则从未预占（成功）。 */
    private void inspectReleaseNoTicket(long orderId, long productId) {
        if (stockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.RELEASE)) {
            return;
        }
        if (stockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.CONFIRM)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }
        if (stockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.RESERVE)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_SYNC_LAG);
        }
    }

    private static String idempotentKey(long orderId, long productId, ProductStockOptTypeEnum optType) {
        return ProductStockIdempotentKeys.of(orderId, productId, optType);
    }

    private static IllegalStateException unexpected(String op, RedisStockResult result) {
        return new IllegalStateException("unexpected " + op + " result: " + result);
    }
}
