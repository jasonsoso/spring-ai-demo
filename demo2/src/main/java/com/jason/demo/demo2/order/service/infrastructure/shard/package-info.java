/**
 * 订单分库分表与订单号基因。
 *
 * <p>{@link com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene}：9 bit / 512 虚拟分片 / 2 库 / 32 表纯函数。
 * {@link com.jason.demo.demo2.order.service.infrastructure.shard.OrderIdGenerator}：位图
 * {@code [41 时间][5 机器][8 序号][9 基因]}，低 9 位为基因。
 * {@link com.jason.demo.demo2.order.service.infrastructure.shard.OrderComplexShardingAlgorithm}：有
 * {@code member_id} 用会员，只有 {@code order_id} 拆基因。
 *
 * <p>{@code ds = virtual % 2}，{@code table = (virtual / 2) % 32}。禁止 {@code table = virtual % 32}。
 */
package com.jason.demo.demo2.order.service.infrastructure.shard;
