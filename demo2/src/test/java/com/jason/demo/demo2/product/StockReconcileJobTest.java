package com.jason.demo.demo2.product;

import com.baomidou.lock.annotation.Lock4j;
import com.jason.demo.demo2.lock.LockKeys;
import com.jason.demo.demo2.lock.SkipIfLockedFailureStrategy;
import com.jason.demo.demo2.product.app.job.StockReconcileJob;
import com.jason.demo.demo2.product.service.core.StockReconcileService;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockReconcileJobTest {

    @Mock
    private ProductStockRepository productStockRepository;
    @Mock
    private StockReconcileService stockReconcileService;

    private ProductStockProperties properties;
    private StockReconcileJob job;

    @BeforeEach
    void setUp() {
        properties = new ProductStockProperties();
        job = new StockReconcileJob(properties, productStockRepository, stockReconcileService);
    }

    @Test
    void run_annotatedWithLock4jSkipIfBusy() throws NoSuchMethodException {
        Method run = StockReconcileJob.class.getMethod("run");
        Lock4j lock = run.getAnnotation(Lock4j.class);
        assertThat(lock).isNotNull();
        assertThat(lock.acquireTimeout()).isZero();
        assertThat(lock.expire()).isEqualTo(60_000L);
        assertThat(lock.failStrategy()).isEqualTo(SkipIfLockedFailureStrategy.class);
        assertThat(lock.keys()).containsExactly(
                "T(com.jason.demo.demo2.lock.LockKeys).stockReconcileKey()");
        assertThat(LockKeys.stockReconcileKey()).isEqualTo("product:stock:reconcile");
    }

    @Test
    void run_skipsWhenRedisHotDisabled() {
        properties.setRedisHotEnabled(false);
        job.run();
        verifyNoInteractions(productStockRepository, stockReconcileService);
    }

    @Test
    void run_reconcilesEachProduct() {
        ProductStock one = new ProductStock();
        one.setProductId(1L);
        ProductStock two = new ProductStock();
        two.setProductId(2L);
        when(productStockRepository.findAll()).thenReturn(List.of(one, two));

        job.run();

        verify(stockReconcileService).reconcileOne(1L);
        verify(stockReconcileService).reconcileOne(2L);
        verify(stockReconcileService, never()).reconcileOne(3L);
    }
}
