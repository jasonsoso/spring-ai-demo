package com.jason.demo.demo2.framework.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

/**
 * Hutool 雪花发号器封装。
 * <p>
 * 生产环境由 {@link SnowflakeIdConfiguration} 注入已分配的 worker/datacenter；
 * 单测可直接 {@code new SnowflakeIdGenerator(workerId, datacenterId)}，无需 Redis。
 */
public class SnowflakeIdGenerator {

    private final Snowflake snowflake;

    /**
     * @param workerId     机器/实例号（0~31），对应 Snowflake workerId
     * @param datacenterId 服务号（0~31），对应 Snowflake datacenterId
     */
    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    /** 生成下一个全局唯一（在本节点号空间内）的 long ID */
    public long nextId() {
        return snowflake.nextId();
    }
}
