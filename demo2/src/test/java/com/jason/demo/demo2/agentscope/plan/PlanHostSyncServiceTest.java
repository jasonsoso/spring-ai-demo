package com.jason.demo.demo2.agentscope.plan;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanHostSyncServiceTest {

    @TempDir
    Path temp;

    DevAgentProperties properties;
    SandboxPlanReader reader;
    PlanHostSyncService service;

    @BeforeEach
    void setUp() {
        properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                temp.toString(),
                "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                new DevAgentProperties.Sandbox(
                        true,
                        "agentscope-java-sandbox:17",
                        "none",
                        "/workspace",
                        ".agentscope/sandbox-snapshots",
                        536870912L,
                        1L));
        reader = mock(SandboxPlanReader.class);
        service = new PlanHostSyncService(properties, reader);
    }

    @Test
    void writesHostPlanOnSuccess() throws Exception {
        when(reader.readPlanMarkdown(eq("u1"), eq("s1")))
                .thenReturn(Optional.of("# Plan\n\ndo thing\n"));

        service.syncAfterPlanWrite("u1", "s1");

        Path hostPlan = temp.resolve("workspace/plans/PLAN.md");
        assertThat(hostPlan).exists();
        assertThat(Files.readString(hostPlan, StandardCharsets.UTF_8))
                .isEqualTo("# Plan\n\ndo thing\n");
    }

    @Test
    void overwritesExistingHostPlan() throws Exception {
        Path hostPlan = temp.resolve("workspace/plans/PLAN.md");
        Files.createDirectories(hostPlan.getParent());
        Files.writeString(hostPlan, "old", StandardCharsets.UTF_8);
        when(reader.readPlanMarkdown(any(), any()))
                .thenReturn(Optional.of("new-content"));

        service.syncAfterPlanWrite("u1", "s1");

        assertThat(Files.readString(hostPlan, StandardCharsets.UTF_8)).isEqualTo("new-content");
    }

    @Test
    void readMissDoesNotThrowAndKeepsOldFile() throws Exception {
        Path hostPlan = temp.resolve("workspace/plans/PLAN.md");
        Files.createDirectories(hostPlan.getParent());
        Files.writeString(hostPlan, "keep-me", StandardCharsets.UTF_8);
        when(reader.readPlanMarkdown(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.syncAfterPlanWrite("u1", "s1")).doesNotThrowAnyException();
        assertThat(Files.readString(hostPlan, StandardCharsets.UTF_8)).isEqualTo("keep-me");
    }

    @Test
    void sandboxDisabledIsNoOp() {
        DevAgentProperties off = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                temp.toString(),
                "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                null);
        PlanHostSyncService disabled = new PlanHostSyncService(off, reader);
        assertThatCode(() -> disabled.syncAfterPlanWrite("u1", "s1")).doesNotThrowAnyException();
        assertThat(temp.resolve("workspace/plans/PLAN.md")).doesNotExist();
    }
}
