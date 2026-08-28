package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.config.OrderProperties;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenKeys;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenPayload;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPlaceTokenStoreTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    JsonMapper jsonMapper = JsonMapper.builder().build();
    OrderProperties properties = new OrderProperties();

    @Test
    void getPreview_blank_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("demo:order:preview:t")).thenReturn(null);
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);
        assertTrue(store.getPreview("t").isEmpty());
    }

    @Test
    void keys_matchSpec() {
        assertEquals("demo:order:preview:abc", OrderPlaceTokenKeys.preview("abc"));
        assertEquals("demo:order:place:lock:abc", OrderPlaceTokenKeys.lock("abc"));
        assertEquals("demo:order:place:result:abc", OrderPlaceTokenKeys.result("abc"));
    }

    @Test
    void getPreview_invalidJson_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("demo:order:preview:t")).thenReturn("not-json");
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);
        assertTrue(store.getPreview("t").isEmpty());
    }

    @Test
    void getPreview_validJson_returnsPayload() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("demo:order:preview:t"))
                .thenReturn("{\"memberId\":1,\"items\":[{\"productId\":1,\"qty\":2,\"sellPrice\":18.00}]}");
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);

        Optional<OrderPlaceTokenPayload> preview = store.getPreview("t");

        assertTrue(preview.isPresent());
        assertEquals(1L, preview.get().memberId());
        assertEquals(1, preview.get().items().size());
        assertEquals(1L, preview.get().items().getFirst().productId());
        assertEquals(2, preview.get().items().getFirst().qty());
        assertEquals(0, new BigDecimal("18.00").compareTo(preview.get().items().getFirst().sellPrice()));
    }

    @Test
    void savePreview_usesSetExLua() {
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);
        OrderPlaceTokenPayload payload = new OrderPlaceTokenPayload(
                1L,
                List.of(new OrderPlaceTokenPayload.Item(1L, 2, new BigDecimal("18.00"))));

        store.savePreview("abc", payload, Duration.ofMinutes(30));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<String>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(
                scriptCaptor.capture(),
                eq(List.of("demo:order:preview:abc")),
                jsonCaptor.capture(),
                eq("1800"));
        assertTrue(scriptCaptor.getValue().getScriptAsString().contains("'EX'"));
        assertFalse(scriptCaptor.getValue().getScriptAsString().contains("'NX'"));
        assertTrue(jsonCaptor.getValue().contains("\"memberId\""));
        assertTrue(jsonCaptor.getValue().contains("\"productId\""));
    }

    @Test
    void tryLock_returnsTrueWhenOk() {
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of("demo:order:place:lock:abc")), eq("abc"), eq("30")))
                .thenReturn("OK");
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);

        assertTrue(store.tryLock("abc", Duration.ofSeconds(30)));
    }

    @Test
    void tryLock_returnsFalseWhenNotOk() {
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of("demo:order:place:lock:abc")), eq("abc"), eq("30")))
                .thenReturn(null);
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);

        assertFalse(store.tryLock("abc", Duration.ofSeconds(30)));
    }

    @Test
    void unlock_deletesLockKey() {
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);

        store.unlock("abc");

        verify(redis).delete("demo:order:place:lock:abc");
    }

    @Test
    void getResult_missing_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("demo:order:place:result:t")).thenReturn(null);
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);

        assertTrue(store.getResult("t").isEmpty());
    }

    @Test
    void getResult_parsesLong() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("demo:order:place:result:t")).thenReturn("55");
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);

        assertEquals(Optional.of(55L), store.getResult("t"));
    }

    @Test
    void saveResult_usesSetExLuaWith24h() {
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);

        store.saveResult("abc", 55L, Duration.ofHours(24));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<String>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(
                scriptCaptor.capture(),
                eq(List.of("demo:order:place:result:abc")),
                eq("55"),
                eq("86400"));
        assertTrue(scriptCaptor.getValue().getScriptAsString().contains("'EX'"));
        assertFalse(scriptCaptor.getValue().getScriptAsString().contains("'NX'"));
    }
}
