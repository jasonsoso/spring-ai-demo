package com.jason.demo.demo2.agentscope.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeCodeReviewerSkillAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void codeReviewerSkillAndSampleJavaExist() throws Exception {
        Path skill = MODULE.resolve("workspace/skills/code-reviewer/SKILL.md");
        Path guide = MODULE.resolve(
                "workspace/skills/code-reviewer/references/java-style-guide.md");
        Path sample = MODULE.resolve("mcp-files/UserProfileFormatter.java");

        assertThat(skill).exists();
        assertThat(guide).exists();
        assertThat(sample).exists();

        String skillMd = Files.readString(skill);
        assertThat(skillMd).contains("name: code-reviewer");
        assertThat(skillMd).contains("load_skill_through_path");
        assertThat(skillMd).contains("## 严重问题");
        assertThat(skillMd).contains("references/java-style-guide.md");
        assertThat(skillMd).doesNotContain("UserProfileFormatter");

        String java = Files.readString(sample);
        assertThat(java).contains("System.out.println");
        assertThat(java).contains("user.get(\"name\")");
    }
}
