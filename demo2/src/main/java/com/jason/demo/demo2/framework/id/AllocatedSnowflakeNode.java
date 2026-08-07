package com.jason.demo.demo2.framework.id;

public record AllocatedSnowflakeNode(
        String applicationName,
        long datacenterId,
        long workerId,
        String instanceId
) {
}
