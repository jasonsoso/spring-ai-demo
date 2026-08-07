package com.jason.demo.demo2.framework.delay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.delay")
public class DelayProperties {

    private String backend = "redisson";
    private Duration defaultDelay = Duration.ofSeconds(30);
    private long scanIntervalMs = 5000;
    private int maxRetry = 3;
    private Duration lockTimeout = Duration.ofSeconds(10);
    private int scanBatchSize = 50;
    private String redissonQueueName = "demo2:delay:queue";
    private String redissonDelayQueueName = "demo2:delay:delayed";

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public Duration getDefaultDelay() {
        return defaultDelay;
    }

    public void setDefaultDelay(Duration defaultDelay) {
        this.defaultDelay = defaultDelay;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    public int getScanBatchSize() {
        return scanBatchSize;
    }

    public void setScanBatchSize(int scanBatchSize) {
        this.scanBatchSize = scanBatchSize;
    }

    public String getRedissonQueueName() {
        return redissonQueueName;
    }

    public void setRedissonQueueName(String redissonQueueName) {
        this.redissonQueueName = redissonQueueName;
    }

    public String getRedissonDelayQueueName() {
        return redissonDelayQueueName;
    }

    public void setRedissonDelayQueueName(String redissonDelayQueueName) {
        this.redissonDelayQueueName = redissonDelayQueueName;
    }
}
