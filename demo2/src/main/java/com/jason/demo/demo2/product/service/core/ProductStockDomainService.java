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
 * MySQL 库存账本。恒等式 {@code stock = actual - withhold}；{@code stock_seq} 只给投影和对账，不是可售。
 * <p>
 * 冷路径 {@link #reserve} / {@link #confirm} / {@link #release} / {@link #adjust} 用行锁串行改账，
 * 流水 before 取锁内快照，after 在内存推演。
 * 热路径 {@link #applyDelta} 不持行锁，仅当 {@code stock_seq = 消息 seq - 1} 才落账，
 * before 用 {@link ProductStock#reverse} 从更新后的行反推。
 * 两条路径都禁止更新成功后再无锁 SELECT 当作 after，否则会读到并发中间态。
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

    /**
     * 冷路径预占。行锁内扣可售、加预占，并写 RESERVE 流水。
     * 幂等键 {@code orderId + productId + RESERVE} 已存在则直接返回。
     * after 由锁内快照内存推演，更新成功后不再无锁 SELECT。
     */
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

    /**
     * 冷路径确认。必须已有未核销的 RESERVE；扣减数量以该流水为准，调用方传入的 qty 不参与。
     * 行锁内扣实物与预占、加已售，可售不变（预占时已扣）。同一订单+商品已确认过则直接返回。
     * 找不到预占流水抛 {@code RESERVE_LOG_NOT_FOUND}。
     */
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

    /**
     * 冷路径释放。没有待核销的 RESERVE 时为空操作（取消一个从未预占成功的订单）。
     * 有预占则行锁归还可售、减预占，数量取流水。
     */
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

    /**
     * 盘点调账。把实物库存改到 {@code targetActual}，可售同步为 {@code targetActual - withhold}。
     * 目标不能为负，也不能低于当前预占，否则在途预占会被抹掉。同一 {@code adjustId} 幂等，重复调用只返回当前行。
     *
     * @return 调账后的内存快照；幂等命中时返回库中当前行
     */
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
     * 热路径投影。把 Redis 闸门已经生效的变更按消息落到 MySQL，不持行锁。
     * <p>
     * 乐观条件是 {@code stock_seq = seq - 1}：命中则 seq 前进并写流水；
     * 未命中且当前 seq 已不小于消息 seq，视为重复投递或乱序后继已入账，直接返回；
     * 否则抛 {@link StockSeqGapException}，由消费侧重试补缺口。
     * RELEASE 即使还没有 RESERVE 流水也必须走这条更新，不能当成冷路径的「从未预占」空成功。
     * CONFIRM 与 RELEASE 互斥，对方流水已存在则冲突。
     * 流水 before 用 {@link ProductStock#reverse} 从更新后的行反推。
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

        // 同一订单不能既确认又释放；乱序时后到的一方直接冲突，不能再改账
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

    /**
     * 查待核销的 RESERVE。没有流水时返回 empty，释放路径当成空操作，而不是抛「预占不存在」。
     */
    private OptionalReserve findPendingReserveOrEmpty(long orderId, long productId) {
        return productStockLogRepository.findPendingReserve(orderId, productId)
                .map(log -> new OptionalReserve(false, log.getChangeQty()))
                .orElseGet(() -> new OptionalReserve(true, 0));
    }

    /**
     * 落一条库存流水。before/after 必须是同一次变更的前后快照（内存推演或 reverse），
     * 幂等键保证同一业务动作只记一次。
     */
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

    /** 库中 stock_seq 为空时按 0 参与比较和 +1。 */
    private static long nullToZero(Long v) {
        return v == null ? 0L : v;
    }

    /** 冷路径释放的查询结果：empty 表示没有待核销预占。 */
    private record OptionalReserve(boolean empty, int qty) {
    }
}
