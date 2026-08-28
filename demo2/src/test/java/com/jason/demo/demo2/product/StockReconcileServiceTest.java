package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.common.ReconcileKindEnum;
import com.jason.demo.demo2.product.service.core.StockReconcileService;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockReconcileServiceTest {

    private static final long PRODUCT_ID = 9001L;

    @Mock
    private RedisStockOps redisStockOps;
    @Mock
    private ProductStockRepository productStockRepository;

    private ProductStockProperties properties;
    private MutableClock clock;
    private StockReconcileService service;

    @BeforeEach
    void setUp() {
        properties = new ProductStockProperties();
        clock = new MutableClock(Instant.parse("2026-08-28T03:00:00Z"));
        service = new StockReconcileService(redisStockOps, productStockRepository, properties, clock);
    }

    @Test
    void seqAhead_isInFlight_evenIfAvailDiffers() {
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(mysql(3L, 100));
        when(redisStockOps.getSeq(PRODUCT_ID)).thenReturn(Optional.of(5L));

        assertEquals(ReconcileKindEnum.IN_FLIGHT, service.reconcileOne(PRODUCT_ID));
    }

    @Test
    void seqEqual_availMismatch() {
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(mysql(5L, 100));
        when(redisStockOps.getSeq(PRODUCT_ID)).thenReturn(Optional.of(5L));
        when(redisStockOps.getAvail(PRODUCT_ID)).thenReturn(Optional.of(77L));

        assertEquals(ReconcileKindEnum.AVAIL_MISMATCH, service.reconcileOne(PRODUCT_ID));
    }

    @Test
    void mysqlAhead() {
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(mysql(4L, 100));
        when(redisStockOps.getSeq(PRODUCT_ID)).thenReturn(Optional.of(2L));

        assertEquals(ReconcileKindEnum.MYSQL_AHEAD, service.reconcileOne(PRODUCT_ID));
    }

    @Test
    void seqEqual_availMatch_ok() {
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(mysql(5L, 100));
        when(redisStockOps.getSeq(PRODUCT_ID)).thenReturn(Optional.of(5L));
        when(redisStockOps.getAvail(PRODUCT_ID)).thenReturn(Optional.of(100L));

        assertEquals(ReconcileKindEnum.OK, service.reconcileOne(PRODUCT_ID));
    }

    @Test
    void inFlightLongerThanThreshold_isSlow() {
        when(productStockRepository.requireByProductId(PRODUCT_ID)).thenReturn(mysql(3L, 100));
        when(redisStockOps.getSeq(PRODUCT_ID)).thenReturn(Optional.of(5L));
        assertEquals(ReconcileKindEnum.IN_FLIGHT, service.reconcileOne(PRODUCT_ID));

        clock.setInstant(clock.instant().plusMillis(properties.getReconcileLagAlarmMs() + 1));

        assertEquals(ReconcileKindEnum.IN_FLIGHT_SLOW, service.reconcileOne(PRODUCT_ID));
    }

    private static ProductStock mysql(long seq, int stock) {
        ProductStock row = new ProductStock();
        row.setProductId(PRODUCT_ID);
        row.setStock(stock);
        row.setStockSeq(seq);
        return row;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
