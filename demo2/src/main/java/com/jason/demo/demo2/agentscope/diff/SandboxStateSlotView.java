package com.jason.demo.demo2.agentscope.diff;

import io.agentscope.core.state.State;

/**
 * 与 AgentScope {@code SessionSandboxStateStore.SandboxStateSlot} 同形的反序列化视图
 *（官方 slot 为 package-private，不能直接引用）。
 */
record SandboxStateSlotView(String json, boolean deleted) implements State {
}
