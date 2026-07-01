package com.jason.demo.demo2.config;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AutoMemoryAgentConfig {

    @Value("${agent.memories.dir:${user.home}/.agent/memories}")
    private String agentMemoriesDir;

    @Value("${agent.auto-memory.chat.model:deepseek-chat}")
    private String autoMemoryChatModel;

    public String getAgentMemoriesDir() {
        return agentMemoriesDir;
    }

    public String getAutoMemoryChatModel() {
        return autoMemoryChatModel;
    }

    /**
     * 占位 Tool，触发 ChatClient 注册 ToolCallingAdvisor（spring-ai#6325 兼容）。
     * AutoMemoryTools 由 AutoMemoryToolsAdvisor 在运行时动态注入。
     */
    @Bean
    public AutoMemoryToolCallingTrigger autoMemoryToolCallingTrigger() {
        return new AutoMemoryToolCallingTrigger();
    }

    public static class AutoMemoryToolCallingTrigger {

        @Tool(description = "Internal placeholder; enables tool-calling for AutoMemoryToolsAdvisor", name = "autoMemoryAdvisorTrigger")
        public String trigger() {
            return "ok";
        }
    }
}
