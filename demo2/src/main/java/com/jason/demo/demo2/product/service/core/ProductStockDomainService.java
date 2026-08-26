package com.jason.demo.demo2.product.service.core;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockLogDO;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockLogRepository;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
        ProductStock before = productStockRepository.requireByProductId(productId);
        if (!productStockRepository.reserve(productId, qty)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT);
        }
        ProductStock after = productStockRepository.requireByProductId(productId);
        after.assertBalance();
        writeLog(before, after, ProductStockOptTypeEnum.RESERVE, orderId, qty, null);
    }

    @Transactional
    public void confirm(long productId, long orderId, int qty) {
        ProductStockLogDO reserve = productStockLogRepository.findPendingReserve(orderId, productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.RESERVE_LOG_NOT_FOUND));
        int effectiveQty = reserve.getChangeQty();
        ProductStock before = productStockRepository.requireByProductId(productId);
        if (!productStockRepository.confirm(productId, effectiveQty)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }
        ProductStock after = productStockRepository.requireByProductId(productId);
        after.assertBalance();
        writeLog(before, after, ProductStockOptTypeEnum.CONFIRM, orderId, effectiveQty, null);
    }

    @Transactional
    public void release(long productId, long orderId) {
        OptionalReserve reserve = findPendingReserveOrEmpty(orderId, productId);
        if (reserve.empty()) {
            return;
        }
        int qty = reserve.qty();
        ProductStock before = productStockRepository.requireByProductId(productId);
        if (!productStockRepository.release(productId, qty)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
        }
        ProductStock after = productStockRepository.requireByProductId(productId);
        after.assertBalance();
        writeLog(before, after, ProductStockOptTypeEnum.RELEASE, orderId, qty, "cancel rollback");
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
            String remarks) {
        ProductStockLogDO log = new ProductStockLogDO();
        log.setLogId(idGenerator.nextId());
        log.setStockId(before.getStockId());
        log.setProductId(before.getProductId());
        log.setOrderId(orderId);
        log.setOptType(optType.name());
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

    private record OptionalReserve(boolean empty, int qty) {
    }
}
