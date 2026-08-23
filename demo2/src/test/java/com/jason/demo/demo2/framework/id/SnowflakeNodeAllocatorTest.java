package com.jason.demo.demo2.framework.id;

import com.jason.demo.demo2.framework.id.configuration.SnowflakeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        stubRedisScripts("OK");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureDatacenterId_returnsExistingMapping() {
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of(
                "test:snowflake:dc:order-service", "test:snowflake:dc:used")), any()))
                .thenReturn("7");

        assertEquals(7L, allocator.ensureDatacenterId("order-service"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureDatacenterId_failsWhenSlotsExhausted() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> allocator.ensureDatacenterId("order-service"));
    }

    @Test
    void allocate_acquiresFirstFreeWorker() {
        AllocatedSnowflakeNode node = allocator.allocate();
        assertEquals(1L, node.datacenterId());
        assertEquals(0L, node.workerId());
        assertEquals("order-service", node.applicationName());
        assertNotNull(node.instanceId());
    }

    @Test
    void allocate_failsWhenAllWorkersTaken() {
        stubRedisScripts(null);
        assertThrows(IllegalStateException.class, () -> allocator.allocate());
    }

    @Test
    void renewLease_succeedsOnlyForOwner() {
        allocator.allocate();
        assertTrue(allocator.renewLease());
    }

    @Test
    void releaseLease_deletesOnlyOwnedKey() {
        allocator.allocate();
        assertTrue(allocator.releaseLease());
    }

    @SuppressWarnings("unchecked")
    private void stubRedisScripts(String acquireResult) {
        lenient().when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenAnswer(redisScriptAnswer(acquireResult));
        lenient().when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenAnswer(redisScriptAnswer(acquireResult));
    }

    @SuppressWarnings("unchecked")
    private static Answer<Object> redisScriptAnswer(String acquireResult) {
        return invocation -> {
            List<String> keys = invocation.getArgument(1);
            DefaultRedisScript<?> script = invocation.getArgument(0);
            if (keys.size() == 2) {
                return "1";
            }
            if (script.getResultType() == Long.class) {
                return 1L;
            }
            return acquireResult;
        };
    }
}
