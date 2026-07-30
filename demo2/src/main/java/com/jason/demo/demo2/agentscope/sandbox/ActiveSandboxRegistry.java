package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 跟踪 create/resume 后尚未 delete 的活跃沙箱，供 plan 宿主同步 live 读取。 */
public final class ActiveSandboxRegistry {

    private final ConcurrentHashMap<Sandbox, String> sessionIdsBySandbox = new ConcurrentHashMap<>();

    public void register(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        SandboxState state = sandbox.getState();
        String sessionId = state == null ? null : state.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionIdsBySandbox.put(sandbox, sessionId);
    }

    public void unregister(Sandbox sandbox) {
        if (sandbox != null) {
            sessionIdsBySandbox.remove(sandbox);
        }
    }

    public Optional<Sandbox> findByAppSessionId(String appSessionId) {
        if (appSessionId == null || appSessionId.isBlank()) {
            return Optional.empty();
        }
        for (var entry : sessionIdsBySandbox.entrySet()) {
            String sid = entry.getValue();
            if (appSessionId.equals(sid)
                    || sid.endsWith("/" + appSessionId)
                    || ("sandbox/session/" + appSessionId).equals(sid)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}
