package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductStockDomainServiceTest {

    private static final long PRODUCT_ID = 9001L;
    private static final long ORDER_ID = 100L;
    private static final long STOCK_ID = 9101L;

    @Mock
    private ProductStockRepository productStockRepository;
    @Mock
    private ProductStockLogRepository productStockLogRepository;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    @InjectMocks
    private ProductStockDomainService service;

    @Test
    void reserve_insufficientStock() {
        when(productStockRepository.requireByProductIdForUpdate(PRODUCT_ID)).thenReturn(stock(100, 0, 100));
        when(productStockRepository.reserve(PRODUCT_ID, 5)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reserve(PRODUCT_ID, ORDER_ID, 5));
        assertEquals(ProductErrorCodeEnum.STOCK_INSUFFICIENT.getCode(), ex.getCode());
    }

    @Test
    void reserve_success_writesLog() {
        ProductStock before = stock(100, 0, 100);
        when(productStockRepository.requireByProductIdForUpdate(PRODUCT_ID)).thenReturn(before);
        when(productStockRepository.reserve(PRODUCT_ID, 5)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(999L);

        service.reserve(PRODUCT_ID, ORDER_ID, 5);

        ArgumentCaptor<ProductStockLogDO> captor = ArgumentCaptor.forClass(ProductStockLogDO.class);
        verify(productStockLogRepository).insertLog(captor.capture());
        ProductStockLogDO log = captor.getValue();
        assertEquals(ProductStockOptTypeEnum.RESERVE.name(), log.getOptType());
        assertEquals(STOCK_ID, log.getStockId());
        assertEquals(5, log.getChangeQty());
        assertEquals(100, log.getBeforeStock());
        assertEquals(95, log.getAfterStock());
        assertEquals("100:9001:RESERVE", log.getIdempotentKey());
    }

    @Test
    void confirm_incrementsSellStock() {
        ProductStockLogDO reserve = new ProductStockLogDO();
        reserve.setChangeQty(2);
        when(productStockLogRepository.findPendingReserve(ORDER_ID, PRODUCT_ID)).thenReturn(Optional.of(reserve));
        ProductStock before = stock(98, 2, 100);
        when(productStockRepository.requireByProductIdForUpdate(PRODUCT_ID)).thenReturn(before);
        when(productStockRepository.confirm(PRODUCT_ID, 2)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(1000L);

        service.confirm(PRODUCT_ID, ORDER_ID, 2);

        verify(productStockRepository).confirm(PRODUCT_ID, 2);
        verify(productStockLogRepository).insertLog(any());
    }

    @Test
    void confirm_idempotent_whenConfirmLogExists() {
        when(productStockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM))
                .thenReturn(true);

        service.confirm(PRODUCT_ID, ORDER_ID, 2);

        verify(productStockRepository, never()).confirm(anyLong(), anyInt());
    }

    @Test
    void release_restoresStock_fromReserveQty() {
        ProductStockLogDO reserve = new ProductStockLogDO();
        reserve.setChangeQty(4);
        when(productStockLogRepository.findPendingReserve(ORDER_ID, PRODUCT_ID)).thenReturn(Optional.of(reserve));
        ProductStock before = stock(96, 4, 100);
        when(productStockRepository.requireByProductIdForUpdate(PRODUCT_ID)).thenReturn(before);
        when(productStockRepository.release(PRODUCT_ID, 4)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(1001L);

        service.release(PRODUCT_ID, ORDER_ID);

        verify(productStockRepository).release(PRODUCT_ID, 4);
    }

    @Test
    void release_idempotent_whenAlreadyReleased() {
        when(productStockLogRepository.findPendingReserve(ORDER_ID, PRODUCT_ID)).thenReturn(Optional.empty());

        service.release(PRODUCT_ID, ORDER_ID);

        verify(productStockRepository, never()).release(anyLong(), anyInt());
    }

    @Test
    void adjust_updatesActual() {
        ProductStock locked = stock(90, 10, 100);
        locked.setStockSeq(3L);
        when(productStockRepository.requireByProductIdForUpdate(PRODUCT_ID)).thenReturn(locked);
        when(productStockRepository.adjustActual(PRODUCT_ID, 80)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(2000L);

        ProductStock result = service.adjust(PRODUCT_ID, 80, 55L);

        assertEquals(80, result.getActualStock());
        assertEquals(70, result.getStock());
        ArgumentCaptor<ProductStockLogDO> captor = ArgumentCaptor.forClass(ProductStockLogDO.class);
        verify(productStockLogRepository).insertLog(captor.capture());
        assertEquals("ADJUST:55", captor.getValue().getIdempotentKey());
    }

    private static ProductStock stock(int available, int withhold, int actual) {
        ProductStock stock = new ProductStock();
        stock.setStockId(STOCK_ID);
        stock.setProductId(PRODUCT_ID);
        stock.setStock(available);
        stock.setWithholdStock(withhold);
        stock.setActualStock(actual);
        stock.setSellStock(10);
        stock.setStockSeq(0L);
        stock.setUpdatedAt(LocalDateTime.now());
        return stock;
    }
}
