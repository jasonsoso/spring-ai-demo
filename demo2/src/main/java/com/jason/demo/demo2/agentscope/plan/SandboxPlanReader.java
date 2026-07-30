package com.jason.demo.demo2.agentscope.plan;

import java.util.Optional;

public interface SandboxPlanReader {
    Optional<String> readPlanMarkdown(String userId, String sessionId);
}
