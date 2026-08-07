package com.jason.demo.demo2.framework.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

public class SnowflakeIdGenerator {

    private final Snowflake snowflake;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
