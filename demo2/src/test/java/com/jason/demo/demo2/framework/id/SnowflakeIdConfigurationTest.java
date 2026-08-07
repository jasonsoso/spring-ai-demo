package com.jason.demo.demo2.framework.id;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnowflakeIdConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    void generator_usesAllocatedNodeIds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("3");
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        SnowflakeProperties props = new SnowflakeProperties();
        SnowflakeNodeAllocator allocator =
                new SnowflakeNodeAllocator(redis, props, "demo2");
        allocator.allocate();

        SnowflakeIdGenerator generator = new SnowflakeIdConfiguration()
                .snowflakeIdGenerator(allocator);

        assertTrue(generator.nextId() > 0);
        allocator.close();
    }
}
