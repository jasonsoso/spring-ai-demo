package com.jason.demo.demo2.product.service.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 热库存开关与出箱/对账参数。redis-hot-enabled=false 时闸门回退 MySQL 行锁。 */
@Data
@ConfigurationProperties(prefix = "app.product.stock")
public class ProductStockProperties {

    private boolean redisHotEnabled = true;
    private long reconcileIntervalMs = 60000;
    private long reconcileLagAlarmMs = 300000;
    private long outboxBlockMs = 2000;
    private int outboxBatchSize = 16;
    private String outboxGroup = "demo2-stock-relay";
    private String outboxConsumer = "relay";
}
