package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockIdempotentKeys;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.common.StockSeqGapException;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockLogDO;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockLogRepository;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductStockApplyDeltaTest {

    @Mock private ProductStockRepository productStockRepository;
    @Mock private ProductStockLogRepository productStockLogRepository;
    @Mock private SnowflakeIdGenerator idGenerator;
    @InjectMocks private ProductStockDomainService service;

    @Test
    void applyDelta_writesLogFromReverseWhenUpdateHits() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.RESERVE, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockRepository.applyReserveDelta(9001L, 5, 4L)).thenReturn(true);
        ProductStock after = stock(95, 5, 100);
        after.setStockSeq(4L);
        when(productStockRepository.requireByProductId(9001L)).thenReturn(after);
        when(idGenerator.nextId()).thenReturn(1L);

        service.applyDelta(event);

        ArgumentCaptor<ProductStockLogDO> captor = ArgumentCaptor.forClass(ProductStockLogDO.class);
        verify(productStockLogRepository).insertLog(captor.capture());
        assertEquals(100, captor.getValue().getBeforeStock());
        assertEquals(95, captor.getValue().getAfterStock());
        assertEquals(event.getIdempotentKey(), captor.getValue().getIdempotentKey());
    }

    @Test
    void applyDelta_skipsWhenSeqAlreadyApplied() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.RESERVE, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockRepository.applyReserveDelta(9001L, 5, 4L)).thenReturn(false);
        ProductStock current = stock(95, 5, 100);
        current.setStockSeq(4L);
        when(productStockRepository.requireByProductId(9001L)).thenReturn(current);

        service.applyDelta(event);

        verify(productStockLogRepository, never()).insertLog(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void applyDelta_throwsGapWhenSeqBehind() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.CONFIRM, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockLogRepository.existsOpt(100L, 9001L, ProductStockOptTypeEnum.RELEASE)).thenReturn(false);
        when(productStockRepository.applyConfirmDelta(9001L, 5, 4L)).thenReturn(false);
        ProductStock current = stock(100, 0, 100);
        current.setStockSeq(2L);
        when(productStockRepository.requireByProductId(9001L)).thenReturn(current);

        assertThrows(StockSeqGapException.class, () -> service.applyDelta(event));
    }

    @Test
    void applyDelta_confirmAfterRelease_conflicts() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.CONFIRM, 2, 3L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockLogRepository.existsOpt(100L, 9001L, ProductStockOptTypeEnum.RELEASE)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.applyDelta(event));
        assertEquals(ProductErrorCodeEnum.STOCK_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void applyDelta_releaseBeforeReserveProjected_throwsGap() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.RELEASE, 2, 2L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockLogRepository.existsOpt(100L, 9001L, ProductStockOptTypeEnum.CONFIRM)).thenReturn(false);
        when(productStockRepository.applyReleaseDelta(9001L, 2, 2L)).thenReturn(false);
        ProductStock current = stock(100, 0, 100);
        current.setStockSeq(0L);
        when(productStockRepository.requireByProductId(9001L)).thenReturn(current);

        assertThrows(StockSeqGapException.class, () -> service.applyDelta(event));
        verify(productStockLogRepository, never()).insertLog(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void applyDelta_idempotentKeyExists_skips() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.RESERVE, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(true);

        service.applyDelta(event);

        verify(productStockRepository, never()).applyReserveDelta(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static StockSyncEvent event(ProductStockOptTypeEnum op, int qty, long seq) {
        return new StockSyncEvent(9001L, 100L, op.name(), qty,
                ProductStockIdempotentKeys.of(100L, 9001L, op), seq);
    }

    private static ProductStock stock(int available, int withhold, int actual) {
        ProductStock stock = new ProductStock();
        stock.setStockId(9101L);
        stock.setProductId(9001L);
        stock.setStock(available);
        stock.setWithholdStock(withhold);
        stock.setActualStock(actual);
        stock.setSellStock(10);
        return stock;
    }
}
