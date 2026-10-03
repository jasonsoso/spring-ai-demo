package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.app.listener.StockSyncMqListener;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.StockSeqGapException;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSyncMqListenerTest {

    @Mock
    private ProductStockDomainService productStockDomainService;
    @Mock
    private JsonMapper jsonMapper;
    @Mock
    private MessageExt messageExt;

    private AccessibleListener listener;

    @BeforeEach
    void setUp() {
        listener = new AccessibleListener(jsonMapper, productStockDomainService);
    }

    @Test
    void applyDelta_success_consumeSuccess() {
        StockSyncEvent event = sampleEvent();

        ConsumeConcurrentlyStatus status = listener.expose(event, messageExt);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
    }

    @Test
    void seqGap_reconsumeLater() {
        StockSyncEvent event = sampleEvent();
        lenient().when(messageExt.getKeys()).thenReturn("9001 key");
        doThrow(new StockSeqGapException(9001L, 4L, 2L))
                .when(productStockDomainService).applyDelta(event);

        ConsumeConcurrentlyStatus status = listener.expose(event, messageExt);

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, status);
    }

    @Test
    void sameProductId_applyDeltaDoesNotOverlap() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        doAnswer(invocation -> {
            int now = inFlight.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(80);
            } finally {
                inFlight.decrementAndGet();
            }
            return null;
        }).when(productStockDomainService).applyDelta(any(StockSyncEvent.class));

        StockSyncEvent event = sampleEvent();
        Thread first = new Thread(() -> listener.expose(event, messageExt));
        Thread second = new Thread(() -> listener.expose(event, messageExt));
        first.start();
        second.start();
        first.join(2000);
        second.join(2000);

        assertEquals(false, first.isAlive());
        assertEquals(false, second.isAlive());
        assertEquals(1, max.get());
    }

    @Test
    void stockConflict_consumeSuccess() {
        StockSyncEvent event = sampleEvent();
        lenient().when(messageExt.getKeys()).thenReturn("9001 key");
        doThrow(new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT))
                .when(productStockDomainService).applyDelta(event);

        ConsumeConcurrentlyStatus status = listener.expose(event, messageExt);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
    }

    @Test
    void differentProductIds_applyDeltaMayOverlap() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        doAnswer(invocation -> {
            barrier.await(2, TimeUnit.SECONDS);
            return null;
        }).when(productStockDomainService).applyDelta(any(StockSyncEvent.class));

        StockSyncEvent firstEvent = new StockSyncEvent(9001L, 100L, "RESERVE", 2, "k1", 4L);
        StockSyncEvent secondEvent = new StockSyncEvent(9002L, 101L, "RESERVE", 2, "k2", 4L);
        Thread first = new Thread(() -> exposeQuietly(firstEvent, error));
        Thread second = new Thread(() -> exposeQuietly(secondEvent, error));
        first.start();
        second.start();
        first.join(3000);
        second.join(3000);

        assertEquals(false, first.isAlive());
        assertEquals(false, second.isAlive());
        assertEquals(null, error.get());
    }

    @Test
    void nullProductId_applyDeltaMayOverlap() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        doAnswer(invocation -> {
            barrier.await(2, TimeUnit.SECONDS);
            return null;
        }).when(productStockDomainService).applyDelta(any(StockSyncEvent.class));

        StockSyncEvent firstEvent = new StockSyncEvent(null, 100L, "RESERVE", 2, "k1", 4L);
        StockSyncEvent secondEvent = new StockSyncEvent(null, 101L, "RESERVE", 2, "k2", 4L);
        Thread first = new Thread(() -> exposeQuietly(firstEvent, error));
        Thread second = new Thread(() -> exposeQuietly(secondEvent, error));
        first.start();
        second.start();
        first.join(3000);
        second.join(3000);

        assertEquals(false, first.isAlive());
        assertEquals(false, second.isAlive());
        assertEquals(null, error.get());
    }

    @Test
    void seqGap_releasesLockForNextMessage() throws Exception {
        StockSyncEvent event = sampleEvent();
        lenient().when(messageExt.getKeys()).thenReturn("9001 key");
        doThrow(new StockSeqGapException(9001L, 4L, 2L))
                .doNothing()
                .when(productStockDomainService).applyDelta(event);

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.expose(event, messageExt));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ConsumeConcurrentlyStatus> next = pool.submit(() -> listener.expose(event, messageExt));
            assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, next.get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private void exposeQuietly(StockSyncEvent event, AtomicReference<Throwable> error) {
        try {
            listener.expose(event, messageExt);
        } catch (Throwable ex) {
            error.set(ex);
        }
    }

    private static StockSyncEvent sampleEvent() {
        return new StockSyncEvent(9001L, 100L, "RESERVE", 2, "100:9001:RESERVE", 4L);
    }

    private static final class AccessibleListener extends StockSyncMqListener {
        private AccessibleListener(JsonMapper jsonMapper, ProductStockDomainService domainService) {
            super(jsonMapper, domainService);
        }

        ConsumeConcurrentlyStatus expose(StockSyncEvent payload, MessageExt messageExt) {
            return handleMessage(payload, "{}", messageExt);
        }
    }
}
