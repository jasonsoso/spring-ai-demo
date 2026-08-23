package com.jason.demo.demo2.framework.id;

import com.jason.demo.demo2.framework.id.configuration.SnowflakeIdConfiguration;
import com.jason.demo.demo2.framework.id.configuration.SnowflakeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnowflakeIdConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    void generator_usesAllocatedNodeIds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenAnswer(SnowflakeIdConfigurationTest::answer);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenAnswer(SnowflakeIdConfigurationTest::answer);

        SnowflakeProperties props = new SnowflakeProperties();
        SnowflakeNodeAllocator allocator =
                new SnowflakeNodeAllocator(redis, props, "demo2");
        allocator.allocate();

        SnowflakeIdGenerator generator = new SnowflakeIdConfiguration()
                .snowflakeIdGenerator(allocator);

        assertTrue(generator.nextId() > 0);
        allocator.close();
    }

    @SuppressWarnings("unchecked")
    private static Object answer(org.mockito.invocation.InvocationOnMock invocation) {
        List<String> keys = invocation.getArgument(1);
        DefaultRedisScript<?> script = invocation.getArgument(0);
        if (keys.size() == 2) {
            return "3";
        }
        if (script.getResultType() == Long.class) {
            return 1L;
        }
        return "OK";
    }
}
