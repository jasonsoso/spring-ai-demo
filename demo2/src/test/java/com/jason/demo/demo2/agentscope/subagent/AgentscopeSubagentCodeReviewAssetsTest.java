package com.jason.demo.demo2.agentscope.subagent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeSubagentCodeReviewAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void subagentRolesAndTravelBudgetSampleExist() throws Exception {
        Path codeReader = MODULE.resolve("workspace/subagents/code-reader.md");
        Path riskReviewer = MODULE.resolve("workspace/subagents/risk-reviewer.md");
        Path testAdvisor = MODULE.resolve("workspace/subagents/test-advisor.md");
        Path sample = MODULE.resolve("mcp-files/TravelBudgetService.java");

        assertThat(codeReader).exists();
        assertThat(riskReviewer).exists();
        assertThat(testAdvisor).exists();
        assertThat(sample).exists();

        String reader = Files.readString(codeReader);
        assertThat(reader).contains("mode: subagent");
        assertThat(reader).contains("list_directory");
        assertThat(reader).contains("read_text_file");
        assertThat(reader).contains("steps:");

        String risk = Files.readString(riskReviewer);
        assertThat(risk).contains("mode: subagent");
        assertThat(risk).contains("tools:");

        String advisor = Files.readString(testAdvisor);
        assertThat(advisor).contains("mode: subagent");
        assertThat(advisor).contains("tools:");

        String java = Files.readString(sample);
        assertThat(java).contains("class TravelBudgetService");
        assertThat(java).contains("System.out.println");
        assertThat(java).contains("travelerContact");
        assertThat(java).contains("request.vip()");
    }
}
