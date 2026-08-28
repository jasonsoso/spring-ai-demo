package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockIdempotentKeys;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.common.RedisStockResult;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductStockHotServiceTest {

    private static final long PRODUCT_ID = 9001L;
    private static final long ORDER_ID = 100L;
    private static final int QTY = 2;

    @Mock
    private RedisStockOps redisStockOps;
    @Mock
    private ProductStockDomainService domainService;
    @Mock
    private ProductStockLogRepository stockLogRepository;
    @Mock
    private ProductDomainService productDomainService;

    @Test
    void reserve_coldPath_delegatesToDomainService() {
        ProductStockHotService service = newService(false);

        service.reserve(PRODUCT_ID, ORDER_ID, QTY);

        verify(domainService).reserve(PRODUCT_ID, ORDER_ID, QTY);
        verifyNoInteractions(redisStockOps);
    }

    @Test
    void reserve_hot_insufficient_throws40003() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.reserve(PRODUCT_ID, ORDER_ID, QTY, reserveKey()))
                .thenReturn(new RedisStockResult(0, "INSUFFICIENT"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reserve(PRODUCT_ID, ORDER_ID, QTY));
        assertEquals(ProductErrorCodeEnum.STOCK_INSUFFICIENT.getCode(), ex.getCode());
        verify(productDomainService).requireOnShelf(PRODUCT_ID);
    }

    @Test
    void reserve_hot_unloaded_throws40010_neverLoadsHash() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.reserve(PRODUCT_ID, ORDER_ID, QTY, reserveKey()))
                .thenReturn(new RedisStockResult(-1, "UNLOADED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reserve(PRODUCT_ID, ORDER_ID, QTY));
        assertEquals(ProductErrorCodeEnum.STOCK_SYNC_LAG.getCode(), ex.getCode());
        verify(redisStockOps, never()).hsetnxHash(anyLong(), anyInt(), anyLong());
        verify(redisStockOps, never()).adjustHash(anyLong(), anyInt(), anyLong());
    }

    @Test
    void confirm_hot_notFound_existingConfirm_succeeds() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.confirm(PRODUCT_ID, ORDER_ID, confirmKey()))
                .thenReturn(new RedisStockResult(0, "NOT_FOUND"));
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM))
                .thenReturn(true);

        assertDoesNotThrow(() -> service.confirm(PRODUCT_ID, ORDER_ID, QTY));
        verify(domainService, never()).confirm(anyLong(), anyLong(), anyInt());
    }

    @Test
    void confirm_hot_notFound_existingRelease_throws40005() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.confirm(PRODUCT_ID, ORDER_ID, confirmKey()))
                .thenReturn(new RedisStockResult(0, "NOT_FOUND"));
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM))
                .thenReturn(false);
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RELEASE))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(PRODUCT_ID, ORDER_ID, QTY));
        assertEquals(ProductErrorCodeEnum.STOCK_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void confirm_hot_notFound_onlyReserve_throws40010() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.confirm(PRODUCT_ID, ORDER_ID, confirmKey()))
                .thenReturn(new RedisStockResult(0, "NOT_FOUND"));
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM))
                .thenReturn(false);
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RELEASE))
                .thenReturn(false);
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RESERVE))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(PRODUCT_ID, ORDER_ID, QTY));
        assertEquals(ProductErrorCodeEnum.STOCK_SYNC_LAG.getCode(), ex.getCode());
    }

    @Test
    void confirm_hot_notFound_none_throws40004() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.confirm(PRODUCT_ID, ORDER_ID, confirmKey()))
                .thenReturn(new RedisStockResult(0, "NOT_FOUND"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(PRODUCT_ID, ORDER_ID, QTY));
        assertEquals(ProductErrorCodeEnum.RESERVE_LOG_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void release_hot_noTicket_existingRelease_succeeds() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.release(PRODUCT_ID, ORDER_ID, releaseKey()))
                .thenReturn(new RedisStockResult(2, "NO_TICKET"));
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RELEASE))
                .thenReturn(true);

        assertDoesNotThrow(() -> service.release(PRODUCT_ID, ORDER_ID));
        verify(domainService, never()).release(anyLong(), anyLong());
    }

    @Test
    void release_hot_noTicket_existingConfirm_throws40005() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.release(PRODUCT_ID, ORDER_ID, releaseKey()))
                .thenReturn(new RedisStockResult(2, "NO_TICKET"));
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RELEASE))
                .thenReturn(false);
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.release(PRODUCT_ID, ORDER_ID));
        assertEquals(ProductErrorCodeEnum.STOCK_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void release_hot_noTicket_onlyReserve_throws40010() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.release(PRODUCT_ID, ORDER_ID, releaseKey()))
                .thenReturn(new RedisStockResult(2, "NO_TICKET"));
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RELEASE))
                .thenReturn(false);
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM))
                .thenReturn(false);
        when(stockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RESERVE))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.release(PRODUCT_ID, ORDER_ID));
        assertEquals(ProductErrorCodeEnum.STOCK_SYNC_LAG.getCode(), ex.getCode());
    }

    @Test
    void release_hot_noTicket_none_succeeds() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.release(PRODUCT_ID, ORDER_ID, releaseKey()))
                .thenReturn(new RedisStockResult(2, "NO_TICKET"));

        assertDoesNotThrow(() -> service.release(PRODUCT_ID, ORDER_ID));
        verify(domainService, never()).release(anyLong(), anyLong());
    }

    @Test
    void overlayAvail_coldPath_returnsEmpty() {
        ProductStockHotService service = newService(false);

        assertTrue(service.overlayAvail(PRODUCT_ID).isEmpty());
        verifyNoInteractions(redisStockOps);
    }

    @Test
    void overlayAvail_hot_mapsGetAvailToInt() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.getAvail(PRODUCT_ID)).thenReturn(Optional.of(77L));

        assertEquals(Optional.of(77), service.overlayAvail(PRODUCT_ID));
    }

    @Test
    void overlayAvail_hot_missingHash_returnsEmpty() {
        ProductStockHotService service = newService(true);
        when(redisStockOps.getAvail(PRODUCT_ID)).thenReturn(Optional.empty());

        assertTrue(service.overlayAvail(PRODUCT_ID).isEmpty());
    }

    private ProductStockHotService newService(boolean redisHotEnabled) {
        ProductStockProperties properties = new ProductStockProperties();
        properties.setRedisHotEnabled(redisHotEnabled);
        return new ProductStockHotService(
                properties, redisStockOps, domainService, stockLogRepository, productDomainService);
    }

    private static String reserveKey() {
        return ProductStockIdempotentKeys.of(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RESERVE);
    }

    private static String confirmKey() {
        return ProductStockIdempotentKeys.of(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM);
    }

    private static String releaseKey() {
        return ProductStockIdempotentKeys.of(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.RELEASE);
    }
}
