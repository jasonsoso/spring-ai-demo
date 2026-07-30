package com.jason.demo.demo2.agentscope.plan;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Service
public class PlanHostSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlanHostSyncService.class);

    private final DevAgentProperties properties;
    private final SandboxPlanReader reader;

    public PlanHostSyncService(DevAgentProperties properties, SandboxPlanReader reader) {
        this.properties = properties;
        this.reader = reader;
    }

    public void syncAfterPlanWrite(String userId, String sessionId) {
        if (!properties.sandbox().enabled()) {
            return;
        }
        try {
            Optional<String> content = reader.readPlanMarkdown(userId, sessionId);
            if (content.isEmpty()) {
                log.warn(
                        "Skip host plan sync: empty/missing sandbox plan. userId={}, sessionId={}",
                        userId,
                        sessionId);
                return;
            }
            Path hostPlan = Path.of(properties.projectRoot())
                    .resolve(properties.workspaceRoot())
                    .resolve("plans")
                    .resolve("PLAN.md")
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(hostPlan.getParent());
            Path tmp = hostPlan.resolveSibling(
                    "PLAN.md.tmp-" + Thread.currentThread().threadId());
            Files.writeString(tmp, content.get(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        tmp,
                        hostPlan,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, hostPlan, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info(
                    "Synced sandbox plan to host. path={}, userId={}, sessionId={}",
                    hostPlan,
                    userId,
                    sessionId);
        } catch (Exception ex) {
            log.warn(
                    "Host plan sync failed. userId={}, sessionId={}, err={}",
                    userId,
                    sessionId,
                    ex.toString());
        }
    }
}
