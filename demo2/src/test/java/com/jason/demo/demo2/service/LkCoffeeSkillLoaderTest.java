package com.jason.demo.demo2.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "agent.lkcoffee.skill=classpath:/.claude/skills/my-coffee/SKILL.md",
        "agent.lkcoffee.enabled=false",
        "app.mcp.client.init-on-startup=false"
})
class LkCoffeeSkillLoaderTest {

    @Autowired
    LkCoffeeSkillLoader skillLoader;

    @Test
    void buildSystemPrompt_containsSkillAndOverrideRules() {
        String prompt = skillLoader.buildSystemPrompt();
        assertThat(prompt).contains("My Coffee");
        assertThat(prompt).contains("demo2 项目覆盖规则");
        assertThat(prompt).contains("禁止读写 ~/.my-coffee/");
        assertThat(prompt).contains("previewOrder 完成后");
    }
}
