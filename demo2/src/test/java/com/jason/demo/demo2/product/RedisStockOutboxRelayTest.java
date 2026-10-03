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
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamEntryDeletionResult;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private ProductStockProperties properties;
    private RedisStockOutboxRelay relay;

    @BeforeEach
    void setUp() {
        properties = new ProductStockProperties();
        relay = new RedisStockOutboxRelay(redis, publisher, properties, "relay-testhost-1");
        lenient().when(redis.opsForStream()).thenReturn(streamOps);
        lenient().when(streamOps.acknowledgeAndDelete(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"),
                any(RedisStreamCommands.XDelOptions.class), any(String[].class)))
                .thenReturn(List.of(StreamEntryDeletionResult.DELETED));
    }

    @Test
    void onRecord_sendSuccess_acknowledges() {
        Map<String, String> fields = sampleFields();

        relay.onRecord(fields, "1-0");

        verify(publisher).sendNow(new StockSyncEvent(9001L, 100L, "RESERVE", 2, "100:9001:RESERVE", 4L));
        verify(streamOps).acknowledgeAndDelete(RedisStockKeys.OUTBOX, "demo2-stock-relay", acked(), "1-0");
    }

    @Test
    void onRecord_ackNotDeleted_doesNotThrow() {
        when(streamOps.acknowledgeAndDelete(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"),
                any(RedisStreamCommands.XDelOptions.class), eq("1-0")))
                .thenReturn(List.of(StreamEntryDeletionResult.NOT_FOUND));

        relay.onRecord(sampleFields(), "1-0");

        verify(publisher).sendNow(any());
        verify(streamOps).acknowledgeAndDelete(RedisStockKeys.OUTBOX, "demo2-stock-relay", acked(), "1-0");
    }

    @Test
    void onRecord_sendFails_doesNotAck() {
        doThrow(new IllegalStateException("mq down")).when(publisher).sendNow(any());

        assertThrows(IllegalStateException.class, () -> relay.onRecord(sampleFields(), "1-0"));

        verify(streamOps, never()).acknowledgeAndDelete(any(), any(), any(), any(String[].class));
    }

    @Test
    void claimIdle_oneSendFails_stillSendsRest() {
        RecordId id1 = RecordId.of("2-0");
        RecordId id2 = RecordId.of("3-0");
        PendingMessage p1 = mock(PendingMessage.class);
        PendingMessage p2 = mock(PendingMessage.class);
        when(p1.getId()).thenReturn(id1);
        when(p2.getId()).thenReturn(id2);
        PendingMessages pending = mock(PendingMessages.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(List.of(p1, p2).iterator());
        when(streamOps.pending(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), any(Range.class),
                eq(16L), eq(Duration.ofSeconds(30)))).thenReturn(pending);

        MapRecord<String, Object, Object> rec1 = MapRecord.create(RedisStockKeys.OUTBOX, body()).withId(id1);
        MapRecord<String, Object, Object> rec2 = MapRecord.create(RedisStockKeys.OUTBOX, body()).withId(id2);
        when(streamOps.claim(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), eq("relay-testhost-1"),
                eq(Duration.ofSeconds(30)), eq(id1), eq(id2))).thenReturn(List.of(rec1, rec2));
        doThrow(new IllegalStateException("mq down")).doNothing().when(publisher).sendNow(any());

        relay.claimIdlePending();

        verify(publisher, times(2)).sendNow(any());
        verify(streamOps, never()).acknowledgeAndDelete(any(), any(), any(), eq("2-0"));
        verify(streamOps).acknowledgeAndDelete(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"),
                any(RedisStreamCommands.XDelOptions.class), eq("3-0"));
    }

    @Test
    void claimIdlePending_pendingThrows_stillAllowsSecondCall() {
        PendingMessages empty = mock(PendingMessages.class);
        when(empty.isEmpty()).thenReturn(true);
        when(streamOps.pending(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), any(Range.class),
                eq(16L), eq(Duration.ofSeconds(30))))
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn(empty);

        assertThrows(RuntimeException.class, () -> relay.claimIdlePending());
        relay.claimIdlePending();

        verify(streamOps, times(2)).pending(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"),
                any(Range.class), eq(16L), eq(Duration.ofSeconds(30)));
    }

    @Test
    void autoStartup_followsHotFlag() {
        properties.setRedisHotEnabled(true);
        assertTrue(relay.isAutoStartup());
        properties.setRedisHotEnabled(false);
        assertFalse(relay.isAutoStartup());
    }

    @Test
    void consumerName_isInjectedNotBarePrefix() {
        assertEquals("relay-testhost-1", relay.consumerName());
        assertNotEquals("relay", relay.consumerName());
    }

    @Test
    void readRequest_manualAck_andNeverCancelsOnError() {
        var request = relay.newReadRequest();
        assertTrue(request instanceof StreamMessageListenerContainer.ConsumerStreamReadRequest<?>);
        var consumerRequest = (StreamMessageListenerContainer.ConsumerStreamReadRequest<String>) request;
        assertFalse(consumerRequest.isAutoAcknowledge());
        assertFalse(consumerRequest.getCancelSubscriptionOnError().test(new RuntimeException("x")));
        assertEquals("demo2-stock-relay", consumerRequest.getConsumer().getGroup());
        assertEquals("relay-testhost-1", consumerRequest.getConsumer().getName());
    }

    @Test
    void pollExecutor_isExecutorService() {
        assertTrue(relay.pollExecutor() instanceof ExecutorService);
    }

    @Test
    void ensureGroup_busyGroup_isIgnored() {
        doThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"))
                .when(streamOps).createGroup(eq(RedisStockKeys.OUTBOX), any(), eq("demo2-stock-relay"));
        relay.ensureGroup();
    }

    @Test
    void ensureGroup_otherError_propagates() {
        doThrow(new IllegalStateException("NOGROUP")).when(streamOps)
                .createGroup(eq(RedisStockKeys.OUTBOX), any(), eq("demo2-stock-relay"));
        assertThrows(IllegalStateException.class, () -> relay.ensureGroup());
    }

    @Test
    void start_createGroupFails_doesNotMarkRunning() {
        doThrow(new IllegalStateException("NOGROUP")).when(streamOps)
                .createGroup(eq(RedisStockKeys.OUTBOX), any(), eq("demo2-stock-relay"));
        assertThrows(IllegalStateException.class, () -> relay.start());
        assertFalse(relay.isRunning());
    }

    @Test
    void onMessage_sendSuccess_acksGroupNotConsumer() {
        MapRecord<String, String, String> record = MapRecord.create(RedisStockKeys.OUTBOX, sampleFields()).withId(RecordId.of("1-0"));
        relay.onMessage(record);
        verify(streamOps).acknowledgeAndDelete(RedisStockKeys.OUTBOX, "demo2-stock-relay", acked(), "1-0");
    }

    @Test
    void shouldReregister_whenSubscriptionInactiveOrFutureDone() {
        Subscription active = mock(Subscription.class);
        when(active.isActive()).thenReturn(true);
        Future<?> live = mock(Future.class);
        when(live.isDone()).thenReturn(false);
        assertFalse(relay.shouldReregister(active, live));
        assertTrue(relay.shouldReregister(null, live));
        when(active.isActive()).thenReturn(false);
        assertTrue(relay.shouldReregister(active, live));
        when(active.isActive()).thenReturn(true);
        when(live.isDone()).thenReturn(true);
        assertTrue(relay.shouldReregister(active, live));
    }

    @Test
    void watchOnce_whenStopped_doesNotChangeConsumerName() {
        relay.stop();
        String before = relay.consumerName();
        relay.watchOnce();
        assertEquals(before, relay.consumerName());
    }

    private static RedisStreamCommands.XDelOptions acked() {
        return RedisStreamCommands.XDelOptions.deletionPolicy(RedisStreamCommands.StreamDeletionPolicy.ACKNOWLEDGED);
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

    private static Map<Object, Object> body() {
        Map<Object, Object> body = new LinkedHashMap<>();
        body.put("productId", "9001");
        body.put("orderId", "100");
        body.put("optType", "RESERVE");
        body.put("qty", "2");
        body.put("idempotentKey", "100:9001:RESERVE");
        body.put("seq", "4");
        return body;
    }
}
