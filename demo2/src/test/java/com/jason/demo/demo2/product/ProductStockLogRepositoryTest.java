package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockLogDO;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductStockLogRepositoryTest {

    @Mock
    private com.jason.demo.demo2.product.service.infrastructure.dao.mapper.ProductStockLogMapper productStockLogMapper;

    @InjectMocks
    private ProductStockLogRepository repository;

    @Test
    void findPendingReserve_returnsReserveWhenNoRelease() {
        ProductStockLogDO reserve = reserveLog(5);
        when(productStockLogMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(reserve);
        when(productStockLogMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);

        Optional<ProductStockLogDO> result = repository.findPendingReserve(100L, 9001L);

        assertTrue(result.isPresent());
        assertEqualsChangeQty(5, result.get());
    }

    @Test
    void findPendingReserve_emptyWhenReleaseExists() {
        ProductStockLogDO reserve = reserveLog(3);
        when(productStockLogMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(reserve);
        when(productStockLogMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);

        Optional<ProductStockLogDO> result = repository.findPendingReserve(100L, 9001L);

        assertFalse(result.isPresent());
    }

    private static ProductStockLogDO reserveLog(int qty) {
        ProductStockLogDO log = new ProductStockLogDO();
        log.setChangeQty(qty);
        log.setOptType(ProductStockOptTypeEnum.RESERVE.name());
        return log;
    }

    private static void assertEqualsChangeQty(int expected, ProductStockLogDO log) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, log.getChangeQty());
    }
}
