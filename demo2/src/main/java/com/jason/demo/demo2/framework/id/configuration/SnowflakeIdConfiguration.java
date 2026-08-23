package com.jason.demo.demo2.framework.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 装配 Snowflake：启动时经 Redis 分配节点号，再创建 {@link SnowflakeIdGenerator}。
 * <p>
 * 分配失败（Redis 不可用 / 槽位满）会在上下文刷新阶段直接失败，禁止降级到固定 (1,1)。
 */
@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakeIdConfiguration {

    /**
     * 先 allocate + 心跳，容器关闭时调用 {@link SnowflakeNodeAllocator#close()} 释放租约。
     */
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

    /**
     * 业务侧注入此 Bean 发号；构造参数顺序为 (workerId, datacenterId)，与 Hutool 一致。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeNodeAllocator allocator) {
        AllocatedSnowflakeNode node = allocator.current();
        return new SnowflakeIdGenerator(node.workerId(), node.datacenterId());
    }
}
