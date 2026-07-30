package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跟踪 create/resume 后尚未 delete 的活跃沙箱，供 plan 宿主同步 live 读取。
 * Docker create 时 sessionId 可能尚未写入 State，因此同时保留 latest 回退
 * （DevAgentService 对沙箱请求串行，同一时刻最多一个活跃沙箱）。
 */
public final class ActiveSandboxRegistry {

    private final ConcurrentHashMap<Sandbox, String> sessionIdsBySandbox = new ConcurrentHashMap<>();
    private final AtomicReference<Sandbox> latest = new AtomicReference<>();

    public void register(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        latest.set(sandbox);
        SandboxState state = sandbox.getState();
        String sessionId = state == null ? null : state.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionIdsBySandbox.put(sandbox, sessionId);
    }

    public void unregister(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        sessionIdsBySandbox.remove(sandbox);
        latest.compareAndSet(sandbox, null);
    }

    public Optional<Sandbox> findByAppSessionId(String appSessionId) {
        if (appSessionId == null || appSessionId.isBlank()) {
            return Optional.empty();
        }
        refreshSessionIds();
        for (var entry : sessionIdsBySandbox.entrySet()) {
            String sid = entry.getValue();
            if (matches(appSessionId, sid)) {
                return Optional.of(entry.getKey());
            }
        }
        Sandbox current = latest.get();
        return Optional.ofNullable(current);
    }

    private void refreshSessionIds() {
        Sandbox current = latest.get();
        if (current == null) {
            return;
        }
        SandboxState state = current.getState();
        String sessionId = state == null ? null : state.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            sessionIdsBySandbox.put(current, sessionId);
        }
    }

    private static boolean matches(String appSessionId, String sid) {
        return appSessionId.equals(sid)
                || sid.endsWith("/" + appSessionId)
                || ("sandbox/session/" + appSessionId).equals(sid);
    }
}
