package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.service.common.RedisStockResult;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockKeys;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStockOpsTest {

    private static final long PRODUCT_ID = 9001L;
    private static final long ORDER_ID = 100L;
    private static final String IDEMPOTENT_KEY = "100:9001:RESERVE";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @InjectMocks
    private RedisStockOps ops;

    @Test
    void reserve_mapsUnloadedOkInsufficient() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(-1L, "UNLOADED"))
                .thenReturn(List.of(1L, "OK"))
                .thenReturn(List.of(0L, "INSUFFICIENT"));

        RedisStockResult unloaded = ops.reserve(PRODUCT_ID, ORDER_ID, 2, IDEMPOTENT_KEY);
        assertEquals(-1, unloaded.code());
        assertEquals("UNLOADED", unloaded.reason());

        RedisStockResult ok = ops.reserve(PRODUCT_ID, ORDER_ID, 2, IDEMPOTENT_KEY);
        assertEquals(1, ok.code());
        assertEquals("OK", ok.reason());

        RedisStockResult insufficient = ops.reserve(PRODUCT_ID, ORDER_ID, 2, IDEMPOTENT_KEY);
        assertEquals(0, insufficient.code());
        assertEquals("INSUFFICIENT", insufficient.reason());
    }

    @Test
    void hsetnxHash_putIfAbsentTrue_writesSeq() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.putIfAbsent(RedisStockKeys.hash(PRODUCT_ID), "avail", "10")).thenReturn(true);

        assertTrue(ops.hsetnxHash(PRODUCT_ID, 10, 5L));
        verify(hashOperations).put(RedisStockKeys.hash(PRODUCT_ID), "seq", "5");
    }

    @Test
    void hsetnxHash_putIfAbsentFalse_doesNotPutSeq() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.putIfAbsent(RedisStockKeys.hash(PRODUCT_ID), "avail", "10")).thenReturn(false);

        assertFalse(ops.hsetnxHash(PRODUCT_ID, 10, 5L));
        verify(hashOperations, never()).put(eq(RedisStockKeys.hash(PRODUCT_ID)), eq("seq"), any());
    }

    @Test
    void adjustHash_runsStockAdjustLua_andMapsOk() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(1L, "OK"));

        RedisStockResult result = ops.adjustHash(PRODUCT_ID, 80, 12L);
        assertEquals(1, result.code());
        assertEquals("OK", result.reason());

        ArgumentCaptor<DefaultRedisScript> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(), eq("80"), eq("12"));
        assertEquals(List.of(RedisStockKeys.hash(PRODUCT_ID)), keysCaptor.getValue());
        String lua = scriptCaptor.getValue().getScriptAsString();
        assertTrue(lua.contains("HSET"));
        assertFalse(lua.contains("XADD"));
        assertTrue(lua.contains("-- ARGV[1]=新可售"));
    }

    @Test
    void getAvailAndGetSeq_blankIsEmpty() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RedisStockKeys.hash(PRODUCT_ID), "avail")).thenReturn("42");
        when(hashOperations.get(RedisStockKeys.hash(PRODUCT_ID), "seq")).thenReturn("  ");

        assertEquals(Optional.of(42L), ops.getAvail(PRODUCT_ID));
        assertEquals(Optional.empty(), ops.getSeq(PRODUCT_ID));
    }
}
