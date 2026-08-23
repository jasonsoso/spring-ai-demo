package com.jason.demo.demo2.framework.id.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Snowflake 节点自动分配配置，对应 {@code app.snowflake.*}。
 * <p>
 * 同环境各微服务应共用同一 {@link #keyPrefix}，靠 {@code spring.application.name} 区分服务；
 * 仅多环境共 Redis 时才按环境改前缀。
 */
@Data
@ConfigurationProperties(prefix = "app.snowflake")
public class SnowflakeProperties {

    /** Redis key 公共前缀，如 app:snowflake */
    private String keyPrefix = "app:snowflake";

    /** worker 租约 TTL（秒）；崩溃后最多这么久号才会被其它实例抢走 */
    private int leaseTtlSeconds = 30;

    /** 心跳续约间隔（秒）；建议约为 leaseTtl 的 1/3 */
    private int heartbeatIntervalSeconds = 10;
}
