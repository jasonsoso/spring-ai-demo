package com.jason.demo.demo2.agentscope.tool;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileChangeToolTest {

    @TempDir
    Path tempDir;

    FileChangeTool tool;
    PermissionContextState context;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("notes"));
        tool = new FileChangeTool(tempDir);
        context = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
    }

    @Test
    void checkPermissions_createUnderNotes_asks() {
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "create", "path", "notes/a.txt", "content", "x"),
                        context))
                .assertNext(d -> {
                    assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.ASK);
                    assertThat(d.getDecisionReason()).contains("safety");
                })
                .verifyComplete();
    }

    @Test
    void checkPermissions_delete_denies() {
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "delete", "path", "notes/a.txt"),
                        context))
                .assertNext(d -> assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.DENY))
                .verifyComplete();
    }

    @Test
    void checkPermissions_pathEscape_denies() {
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "write", "path", "notes/../application.yml", "content", "x"),
                        context))
                .assertNext(d -> assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.DENY))
                .verifyComplete();
    }

    @Test
    void checkPermissions_absolutePath_denies() {
        String abs = tempDir.resolve("notes/a.txt").toAbsolutePath().toString();
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "create", "path", abs, "content", "x"),
                        context))
                .assertNext(d -> assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.DENY))
                .verifyComplete();
    }

    @Test
    void callAsync_writesFileUnderNotes() throws Exception {
        ToolUseBlock use = ToolUseBlock.builder()
                .id("c1")
                .name("request_file_change")
                .input(Map.of(
                        "operation", "create",
                        "path", "notes/permission-demo.txt",
                        "content", "AgentScope Permission HITL 已通过。"))
                .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();

        StepVerifier.create(tool.callAsync(param))
                .assertNext(result -> assertThat(result).isInstanceOf(ToolResultBlock.class))
                .verifyComplete();

        Path written = tempDir.resolve("notes/permission-demo.txt");
        assertThat(written).exists();
        assertThat(Files.readString(written, StandardCharsets.UTF_8))
                .isEqualTo("AgentScope Permission HITL 已通过。");
    }
}
