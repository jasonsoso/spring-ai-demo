package com.jason.demo.demo2.agentscope.plan;

import com.jason.demo.demo2.agentscope.sandbox.ActiveSandboxRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LiveSandboxPlanReader implements SandboxPlanReader {

    private static final Logger log = LoggerFactory.getLogger(LiveSandboxPlanReader.class);
    private static final String PLAN_RELATIVE = "plans/PLAN.md";

    private final ActiveSandboxRegistry registry;

    public LiveSandboxPlanReader(ActiveSandboxRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<String> readPlanMarkdown(String userId, String sessionId) {
        Optional<Sandbox> sandbox = registry.findByAppSessionId(sessionId);
        if (sandbox.isEmpty()) {
            log.warn("No active sandbox for plan sync. userId={}, sessionId={}", userId, sessionId);
            return Optional.empty();
        }
        try {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .build();
            ExecResult result = sandbox.get().exec(ctx, "cat " + PLAN_RELATIVE, 15);
            if (result == null || !result.ok()) {
                log.warn(
                        "Sandbox cat plan failed. userId={}, sessionId={}, exit={}, stderr={}",
                        userId,
                        sessionId,
                        result == null ? null : result.exitCode(),
                        result == null ? null : result.stderr());
                return Optional.empty();
            }
            String stdout = result.stdout();
            if (stdout == null || stdout.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(stdout);
        } catch (Exception ex) {
            log.warn(
                    "Sandbox plan read threw. userId={}, sessionId={}, err={}",
                    userId,
                    sessionId,
                    ex.toString());
            return Optional.empty();
        }
    }
}
