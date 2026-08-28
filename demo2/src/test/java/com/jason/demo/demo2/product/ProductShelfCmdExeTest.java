package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.app.executor.ProductAdjustStockCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductOffShelfCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductOnShelfCmdExe;
import com.jason.demo.demo2.product.app.vo.res.AdjustStockResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductShelfResVO;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductShelfCmdExeTest {

    private static final long PRODUCT_ID = 9001L;

    @Mock
    private ProductDomainService productDomainService;
    @Mock
    private ProductStockRepository productStockRepository;
    @Mock
    private RedisStockOps redisStockOps;
    @Mock
    private ProductStockDomainService productStockDomainService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    @InjectMocks
    private ProductOnShelfCmdExe onShelfCmdExe;
    @InjectMocks
    private ProductOffShelfCmdExe offShelfCmdExe;
    @InjectMocks
    private ProductAdjustStockCmdExe adjustStockCmdExe;

    @Test
    void onShelf_createdHash_thenOnShelf() {
        when(productDomainService.requireProduct(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.OFF_SHELF));
        ProductStock stock = stock(100, 0L);
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(stock);
        when(redisStockOps.hsetnxHash(PRODUCT_ID, 100, 0L)).thenReturn(true);
        when(productDomainService.onShelf(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.ON_SHELF));

        ProductShelfResVO res = onShelfCmdExe.execute(PRODUCT_ID);

        assertEquals(ProductStatusEnum.ON_SHELF.name(), res.getStatus());
        verify(productDomainService).onShelf(PRODUCT_ID);
        verify(redisStockOps, never()).adjustHash(PRODUCT_ID, 100, 0L);
    }

    @Test
    void onShelf_existingHash_doesNotAdjust() {
        when(productDomainService.requireProduct(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.OFF_SHELF));
        ProductStock stock = stock(100, 3L);
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(stock);
        when(redisStockOps.hsetnxHash(PRODUCT_ID, 100, 3L)).thenReturn(false);
        when(productDomainService.onShelf(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.ON_SHELF));

        onShelfCmdExe.execute(PRODUCT_ID);

        verify(redisStockOps, never()).adjustHash(PRODUCT_ID, 100, 3L);
        verify(productDomainService).onShelf(PRODUCT_ID);
    }

    @Test
    void offShelf_doesNotWriteRedis() {
        when(productDomainService.offShelf(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.OFF_SHELF));

        offShelfCmdExe.execute(PRODUCT_ID);

        verify(redisStockOps, never()).hsetnxHash(PRODUCT_ID, 0, 0L);
        verify(redisStockOps, never()).adjustHash(PRODUCT_ID, 0, 0L);
    }

    @Test
    void adjust_onShelf_requiresOffShelf() {
        when(productDomainService.requireProduct(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.ON_SHELF));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adjustStockCmdExe.execute(PRODUCT_ID, 80));
        assertEquals(ProductErrorCodeEnum.ADJUST_REQUIRES_OFF_SHELF.getCode(), ex.getCode());
        verify(productStockDomainService, never()).adjust(PRODUCT_ID, 80, 1L);
    }

    @Test
    void adjust_seqLag_throws40010() {
        when(productDomainService.requireProduct(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.OFF_SHELF));
        ProductStock mysql = stock(90, 3L);
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(mysql);
        when(redisStockOps.getSeq(PRODUCT_ID)).thenReturn(Optional.of(5L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adjustStockCmdExe.execute(PRODUCT_ID, 80));
        assertEquals(ProductErrorCodeEnum.STOCK_SYNC_LAG.getCode(), ex.getCode());
        verify(productStockDomainService, never()).adjust(PRODUCT_ID, 80, 1L);
    }

    @Test
    void adjust_success_hsetsHash() {
        when(productDomainService.requireProduct(PRODUCT_ID)).thenReturn(product(ProductStatusEnum.OFF_SHELF));
        ProductStock mysql = stock(90, 3L);
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(mysql);
        when(redisStockOps.getSeq(PRODUCT_ID)).thenReturn(Optional.of(3L));
        when(idGenerator.nextId()).thenReturn(55L);
        ProductStock after = stock(70, 4L);
        after.setActualStock(80);
        after.setWithholdStock(10);
        when(productStockDomainService.adjust(PRODUCT_ID, 80, 55L)).thenReturn(after);

        AdjustStockResVO res = adjustStockCmdExe.execute(PRODUCT_ID, 80);

        assertEquals(80, res.getActualStock());
        verify(redisStockOps).adjustHash(PRODUCT_ID, 70, 4L);
    }

    private static Product product(ProductStatusEnum status) {
        Product product = new Product();
        product.setProductId(PRODUCT_ID);
        product.setStatus(status.name());
        return product;
    }

    private static ProductStock stock(int available, long seq) {
        ProductStock stock = new ProductStock();
        stock.setProductId(PRODUCT_ID);
        stock.setStock(available);
        stock.setStockSeq(seq);
        return stock;
    }
}
