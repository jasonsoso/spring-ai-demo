package com.jason.demo.demo2.config;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * Agent Skills 配置（Spring AI 2.0 系列教程 · Agent Skills）
 * <p>
 * SkillsTool 扫描 Cursor 用户 skills 目录及 classpath 内置示例 skills。
 */
@Configuration
public class SkillsAgentConfig {

    @Bean
    public ToolCallback skillsToolCallback(@Value("${agent.skills.dirs}") List<Resource> agentSkillsDirs) {
        return SkillsTool.builder()
                .addSkillsResources(agentSkillsDirs)
                .build();
    }

    @Bean
    public ShellTools shellTools() {
        return ShellTools.builder().build();
    }

    @Bean
    public FileSystemTools fileSystemTools() {
        return FileSystemTools.builder().build();
    }

    @Bean
    public GlobTool globTool() {
        return GlobTool.builder().build();
    }

    @Bean
    public GrepTool grepTool() {
        return GrepTool.builder().build();
    }
}
