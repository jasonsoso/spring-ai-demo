package com.jason.demo.demo2.agentscope.plan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopePlanModeAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void plansDirectorySkeletonExists() {
        Path keep = MODULE.resolve("workspace/plans/.gitkeep");
        assertThat(keep).exists();
    }

    @Test
    void agentsMdDocumentsPlanModeFlow() throws Exception {
        String agents = Files.readString(MODULE.resolve("workspace/AGENTS.md"));
        assertThat(agents).contains("Plan Mode");
        assertThat(agents).contains("plan_write");
        assertThat(agents).contains("plans/PLAN.md");
        assertThat(agents).contains("plan_exit");
        // 规划阶段允许沙箱只读列举/搜索；不得再写「禁止 grep_files」这类与 Plan Mode 冲突的硬句
        assertThat(agents).contains("list_files");
        assertThat(agents).doesNotContain("禁止调用任何 MCP 文件工具，包括");
    }

    @Test
    void frontendExposesPlanModeSample() throws Exception {
        String js = Files.readString(MODULE.resolve("src/main/resources/static/js/tabs/agentscope.js"));
        String html = Files.readString(MODULE.resolve("src/main/resources/static/index.html"));
        assertThat(js).contains("plan-user-017");
        assertThat(js).contains("plan-session-017");
        assertThat(js).contains("方案确认前不要改代码");
        assertThat(html).contains("fillAgentscopeSample(15)");
    }
}
