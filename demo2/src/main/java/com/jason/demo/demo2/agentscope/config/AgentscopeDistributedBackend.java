package com.jason.demo.demo2.agentscope.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;

/**
 * AgentScope 分布式后端的启动结果描述：要么走本地降级，要么走 PostgreSQL 远程。
 * <p>
 * Factory 探测开关 / PG 后只产出这两种形态之一；{@code AgentScopeConfig} 再按类型装配
 * {@code HarnessAgent}（remote → {@code distributedStore} + 远程 Workspace；local → 仅
 * {@code stateStore}）。
 * <p>
 * <b>用到的语言特性（都不是 JDK 21 首发）：</b>
 * <ul>
 *   <li>{@code sealed interface} + {@code permits}：密封接口，只允许列出的实现类 ——
 *       <b>Java 17</b></li>
 *   <li>{@code record}：不可变数据载体，自动生成构造器 / getter / equals ——
 *       <b>Java 16</b></li>
 *   <li>嵌套在接口里的 {@code Local} / {@code Remote}：两种互斥结果，避免散落 if/else
 *       和「可能为 null 的 DistributedStore」</li>
 * </ul>
 * 装配处可用模式匹配（Java 16+）：
 * {@code if (backend instanceof Remote remote) { ... remote.distributedStore() ... }}
 * JDK 21 只是本项目的编译/运行版本，不是这些语法的引入版本。
 */
public sealed interface AgentscopeDistributedBackend
        permits AgentscopeDistributedBackend.Local, AgentscopeDistributedBackend.Remote {

    /** 会话状态存储；local / remote 都必须提供，供 DevAgentService HITL confirm 与 Harness 共用。 */
    AgentStateStore stateStore();

    /**
     * 本地降级：内存会话 + 默认本地 Workspace（开关关闭，或 PG 探测失败）。
     */
    record Local(AgentStateStore stateStore) implements AgentscopeDistributedBackend {
    }

    /**
     * 远程成功：已钉住同一 {@link AgentStateStore} 与 BaseStore 的 {@link DistributedStore}。
     * <p>
     * {@code stateStore} 必须与 {@code distributedStore.agentStateStore()} 为同一实例，
     * 否则 HITL confirm 与 Harness 会各用一份 store。
     */
    record Remote(DistributedStore distributedStore, AgentStateStore stateStore)
            implements AgentscopeDistributedBackend {
    }
}
