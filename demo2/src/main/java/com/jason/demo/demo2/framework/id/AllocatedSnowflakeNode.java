package com.jason.demo.demo2.framework.id;

/**
 * 一次成功分配的 Snowflake 节点身份。
 *
 * @param applicationName 服务名（{@code spring.application.name}）
 * @param datacenterId    服务维度节点号 0~31
 * @param workerId        实例维度节点号 0~31
 * @param instanceId      本进程 UUID，用于租约持有者校验（续约/释放）
 */
public record AllocatedSnowflakeNode(
        String applicationName,
        long datacenterId,
        long workerId,
        String instanceId
) {
}
