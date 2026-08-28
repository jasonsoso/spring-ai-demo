package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.app.listener.RedisStockOutboxRelay;
import com.jason.demo.demo2.product.service.infrastructure.config.ProductStockProperties;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEventPublisher;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStockOutboxRelayTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private StockSyncEventPublisher publisher;
    @SuppressWarnings("rawtypes")
    @Mock
    private StreamOperations streamOps;
    @Mock
    private PendingMessage pendingMessage;

    private ProductStockProperties properties;
    private RedisStockOutboxRelay relay;

    @BeforeEach
    void setUp() {
        properties = new ProductStockProperties();
        relay = new RedisStockOutboxRelay(redis, publisher, properties);
        lenient().when(redis.opsForStream()).thenReturn(streamOps);
    }

    @Test
    void onRecord_sendSuccess_acknowledges() {
        Map<String, String> fields = sampleFields();

        relay.onRecord(fields, "1-0");

        verify(publisher).sendNow(new StockSyncEvent(9001L, 100L, "RESERVE", 2, "100:9001:RESERVE", 4L));
        verify(streamOps).acknowledge(RedisStockKeys.OUTBOX, "demo2-stock-relay", "1-0");
    }

    @Test
    void onRecord_sendFails_doesNotAck() {
        doThrow(new IllegalStateException("mq down")).when(publisher).sendNow(any());

        assertThrows(IllegalStateException.class, () -> relay.onRecord(sampleFields(), "1-0"));

        verify(streamOps, never()).acknowledge(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), eq("1-0"));
    }

    @Test
    void claimIdle_sendFails_doesNotAck() {
        RecordId id = RecordId.of("2-0");
        when(pendingMessage.getId()).thenReturn(id);
        when(pendingMessage.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(1));
        PendingMessages pending = mock(PendingMessages.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(List.of(pendingMessage).iterator());
        when(streamOps.pending(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), any(Range.class), anyLong()))
                .thenReturn(pending);
        Map<Object, Object> body = new java.util.LinkedHashMap<>();
        body.put("productId", "9001");
        body.put("orderId", "100");
        body.put("optType", "RESERVE");
        body.put("qty", "2");
        body.put("idempotentKey", "100:9001:RESERVE");
        body.put("seq", "4");
        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> claimed = MapRecord.create(RedisStockKeys.OUTBOX, body).withId(id);
        when(streamOps.claim(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), eq("relay"),
                eq(Duration.ofSeconds(30)), eq(id)))
                .thenReturn(List.of(claimed));
        doThrow(new IllegalStateException("mq down")).when(publisher).sendNow(any());

        assertThrows(IllegalStateException.class, () -> relay.claimIdlePending());

        verify(streamOps, never()).acknowledge(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), eq("2-0"));
    }

    @Test
    void autoStartup_followsHotFlag() {
        properties.setRedisHotEnabled(true);
        assertTrue(relay.isAutoStartup());
        properties.setRedisHotEnabled(false);
        assertFalse(relay.isAutoStartup());
    }

    private static Map<String, String> sampleFields() {
        return Map.of(
                "productId", "9001",
                "orderId", "100",
                "optType", "RESERVE",
                "qty", "2",
                "idempotentKey", "100:9001:RESERVE",
                "seq", "4");
    }
}
