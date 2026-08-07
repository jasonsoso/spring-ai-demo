package com.jason.demo.demo2.framework.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakeIdConfiguration {

    @Bean(destroyMethod = "close")
    public SnowflakeNodeAllocator snowflakeNodeAllocator(
            StringRedisTemplate stringRedisTemplate,
            SnowflakeProperties properties,
            @Value("${spring.application.name}") String applicationName) {
        SnowflakeNodeAllocator allocator =
                new SnowflakeNodeAllocator(stringRedisTemplate, properties, applicationName);
        allocator.allocate();
        allocator.startHeartbeat();
        return allocator;
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeNodeAllocator allocator) {
        AllocatedSnowflakeNode node = allocator.current();
        return new SnowflakeIdGenerator(node.workerId(), node.datacenterId());
    }
}
