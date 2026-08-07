package com.jason.demo.demo2.framework.id;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnowflakeNodeAllocatorTest {

    @Mock
    private StringRedisTemplate redis;

    private SnowflakeProperties properties;
    private SnowflakeNodeAllocator allocator;

    @BeforeEach
    void setUp() {
        properties = new SnowflakeProperties();
        properties.setKeyPrefix("test:snowflake");
        properties.setLeaseTtlSeconds(30);
        properties.setHeartbeatIntervalSeconds(10);
        allocator = new SnowflakeNodeAllocator(redis, properties, "order-service");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureDatacenterId_returnsExistingMapping() {
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of(
                "test:snowflake:dc:order-service", "test:snowflake:dc:used"))))
                .thenReturn("7");

        assertEquals(7L, allocator.ensureDatacenterId("order-service"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureDatacenterId_failsWhenSlotsExhausted() {
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> allocator.ensureDatacenterId("order-service"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allocate_acquiresFirstFreeWorker() {
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("1");

        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(
                eq("test:snowflake:worker:1:0"), anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(true);

        AllocatedSnowflakeNode node = allocator.allocate();
        assertEquals(1L, node.datacenterId());
        assertEquals(0L, node.workerId());
        assertEquals("order-service", node.applicationName());
        assertNotNull(node.instanceId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allocate_failsWhenAllWorkersTaken() {
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("1");
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> allocator.allocate());
    }

    @Test
    @SuppressWarnings("unchecked")
    void renewLease_succeedsOnlyForOwner() {
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("1");
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        allocator.allocate();

        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);
        assertTrue(allocator.renewLease());
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseLease_deletesOnlyOwnedKey() {
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("1");
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        allocator.allocate();

        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);
        assertTrue(allocator.releaseLease());
    }
}
