/**
 * 订单分库分表与订单号基因。
 *
 * <p>{@link com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene} 写死
 * 9 bit / 512 虚拟分片 / 2 库 / 32 表；{@link com.jason.demo.demo2.order.service.infrastructure.shard.OrderIdGenerator}
 * 把基因嵌进雪花低位；{@link com.jason.demo.demo2.order.service.infrastructure.shard.OrderComplexShardingAlgorithm}
 * 由 ShardingSphere 反射创建（无 Spring 注入），有 {@code member_id} 用会员、只有 {@code order_id} 则拆基因。
 *
 * <p>公式：{@code ds = virtual % 2}，{@code table = (virtual / 2) % 32}。禁止 {@code table = virtual % 32}
 *（2 与 32 不互质，会出现一库只落偶数表）。
 */
package com.jason.demo.demo2.order.service.infrastructure.shard;
