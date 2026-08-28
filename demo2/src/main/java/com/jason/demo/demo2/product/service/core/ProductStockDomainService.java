package com.jason.demo.demo2.product.service.core;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockIdempotentKeys;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.common.StockSeqGapException;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockLogDO;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockLogRepository;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * MySQL 库存账本。冷路径/ADJUST 用行锁；热路径投影用 {@link #applyDelta}（无 FOR UPDATE）。
 * 流水 before/after 来自内存推演或 reverse，禁止二次无锁 SELECT 当 after。
 */
@Service
public class ProductStockDomainService {

    private final ProductStockRepository productStockRepository;
    private final ProductStockLogRepository productStockLogRepository;
    private final SnowflakeIdGenerator idGenerator;

    public ProductStockDomainService(
            ProductStockRepository productStockRepository,
            ProductStockLogRepository productStockLogRepository,
            SnowflakeIdGenerator idGenerator) {
        this.productStockRepository = productStockRepository;
        this.productStockLogRepository = productStockLogRepository;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public void reserve(long productId, long orderId, int qty) {
        if (qty <= 0) {
            throw new BusinessException(CommonErrorCodeEnum.BAD_REQUEST, "qty must be positive");
        }
        String key = ProductStockIdempotentKeys.of(orderId, productId, ProductStockOptTypeEnum.RESERVE);
        if (productStockLogRepository.existsByIdempotentKey(key)) {
            return;
        }
        ProductStock before = productStockRepository.requireByProductIdForUpdate(productId);
        if (!productStockRepository.reserve(productId, qty)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT);
        }
        // after 用内存推演，不再无锁 SELECT（会读到并发中间态）
        ProductStock after = before.copy().applyReserve(qty);
        after.setStockSeq(nullToZero(before.getStockSeq()) + 1);
        after.assertBalance();
        writeLog(before, after, ProductStockOptTypeEnum.RESERVE, orderId, qty, null, key);
    }

    @Transactional
    public void confirm(long productId, long orderId, int qty) {
        if (productStockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.CONFIRM)) {
            return;
        }
        ProductStockLogDO reserve = productStockLogRepository.findPendingReserve(orderId, productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.RESERVE_LOG_NOT_FOUND));
        int effectiveQty = reserve.getChangeQty(); // 以预占流水为准，忽略调用方传入的 qty
        ProductStock before = productStockRepository.requireByProductIdForUpdate(productId);
        if (!productStockRepository.confirm(productId, effectiveQty)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }
        ProductStock after = before.copy().applyConfirm(effectiveQty);
        after.setStockSeq(nullToZero(before.getStockSeq()) + 1);
        after.assertBalance();
        String key = ProductStockIdempotentKeys.of(orderId, productId, ProductStockOptTypeEnum.CONFIRM);
        writeLog(before, after, ProductStockOptTypeEnum.CONFIRM, orderId, effectiveQty, null, key);
    }

    @Transactional
    public void release(long productId, long orderId) {
        OptionalReserve reserve = findPendingReserveOrEmpty(orderId, productId);
        if (reserve.empty()) {
            return; // 冷路径：从未预占则释放是空操作
        }
        int qty = reserve.qty();
        ProductStock before = productStockRepository.requireByProductIdForUpdate(productId);
        if (!productStockRepository.release(productId, qty)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }
        ProductStock after = before.copy().applyRelease(qty);
        after.setStockSeq(nullToZero(before.getStockSeq()) + 1);
        after.assertBalance();
        String key = ProductStockIdempotentKeys.of(orderId, productId, ProductStockOptTypeEnum.RELEASE);
        writeLog(before, after, ProductStockOptTypeEnum.RELEASE, orderId, qty, "cancel rollback", key);
    }

    @Transactional
    public ProductStock adjust(long productId, int targetActual, long adjustId) {
        String key = ProductStockIdempotentKeys.ofAdjust(adjustId);
        if (productStockLogRepository.existsByIdempotentKey(key)) {
            return productStockRepository.requireByProductId(productId);
        }
        ProductStock before = productStockRepository.requireByProductIdForUpdate(productId);
        if (targetActual < 0 || targetActual < before.getWithholdStock()) {
            throw new BusinessException(ProductErrorCodeEnum.ADJUST_INVALID_TARGET);
        }
        if (!productStockRepository.adjustActual(productId, targetActual)) {
            throw new BusinessException(ProductErrorCodeEnum.ADJUST_INVALID_TARGET);
        }
        ProductStock after = before.copy().applyAdjust(targetActual);
        after.setStockSeq(nullToZero(before.getStockSeq()) + 1);
        after.assertBalance();
        writeLog(before, after, ProductStockOptTypeEnum.ADJUST, 0L, Math.abs(targetActual - before.getActualStock()),
                "adjust", key);
        return after;
    }

    /**
     * 热路径投影：仅当 mysql.stock_seq = 消息 seq-1 才应用。
     * RELEASE 即使尚无 RESERVE 流水也要走乐观更新；先到的消息靠缺口重试，不能当「从未预占」直接成功。
     */
    @Transactional
    public void applyDelta(StockSyncEvent event) {
        if (productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())) {
            return;
        }
        ProductStockOptTypeEnum op = ProductStockOptTypeEnum.valueOf(event.getOptType());
        long productId = event.getProductId();
        long orderId = event.getOrderId();
        int qty = event.getQty();
        long seq = event.getSeq();

        if (op == ProductStockOptTypeEnum.CONFIRM
                && productStockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.RELEASE)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }
        if (op == ProductStockOptTypeEnum.RELEASE
                && productStockLogRepository.existsOpt(orderId, productId, ProductStockOptTypeEnum.CONFIRM)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }

        boolean hit = switch (op) {
            case RESERVE -> productStockRepository.applyReserveDelta(productId, qty, seq);
            case CONFIRM -> productStockRepository.applyConfirmDelta(productId, qty, seq);
            case RELEASE -> productStockRepository.applyReleaseDelta(productId, qty, seq);
            default -> throw new IllegalArgumentException("cannot applyDelta " + op);
        };

        if (hit) {
            // 乐观更新已成功，无行锁；after 用当前行，before 用 reverse 反推，避免无锁二次 SELECT 读到别人的中间态
            ProductStock after = productStockRepository.requireByProductId(productId);
            ProductStock before = ProductStock.reverse(after, op, qty);
            after.assertBalance();
            writeLog(before, after, op, orderId, qty, null, event.getIdempotentKey());
            return;
        }

        ProductStock current = productStockRepository.requireByProductId(productId);
        if (nullToZero(current.getStockSeq()) >= seq) {
            // 0 行且 seq 已追上：重复投递或乱序后继已入账
            return;
        }
        throw new StockSeqGapException(productId, seq, current.getStockSeq());
    }

    private OptionalReserve findPendingReserveOrEmpty(long orderId, long productId) {
        return productStockLogRepository.findPendingReserve(orderId, productId)
                .map(log -> new OptionalReserve(false, log.getChangeQty()))
                .orElseGet(() -> new OptionalReserve(true, 0));
    }

    private void writeLog(
            ProductStock before,
            ProductStock after,
            ProductStockOptTypeEnum optType,
            long orderId,
            int changeQty,
            String remarks,
            String idempotentKey) {
        ProductStockLogDO log = new ProductStockLogDO();
        log.setLogId(idGenerator.nextId());
        log.setStockId(before.getStockId());
        log.setProductId(before.getProductId());
        log.setOrderId(orderId);
        log.setOptType(optType.name());
        log.setIdempotentKey(idempotentKey);
        log.setChangeQty(changeQty);
        log.setBeforeActual(before.getActualStock());
        log.setAfterActual(after.getActualStock());
        log.setBeforeStock(before.getStock());
        log.setAfterStock(after.getStock());
        log.setBeforeWithhold(before.getWithholdStock());
        log.setAfterWithhold(after.getWithholdStock());
        log.setBeforeSell(before.getSellStock());
        log.setAfterSell(after.getSellStock());
        log.setRemarks(remarks);
        log.setCreatedAt(LocalDateTime.now());
        productStockLogRepository.insertLog(log);
    }

    private static long nullToZero(Long v) {
        return v == null ? 0L : v;
    }

    private record OptionalReserve(boolean empty, int qty) {
    }
}
