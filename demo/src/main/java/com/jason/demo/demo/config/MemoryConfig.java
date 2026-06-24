package com.jason.demo.demo.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 聊天记忆配置：内存型存储 + 滑动窗口（文章：Spring AI 聊天记忆之带记忆能力的智能行程规划 Agent）
 */
@Configuration
public class MemoryConfig {

    /**
     * 配置内存型聊天记忆：
     * 1. 存储介质：InMemoryChatMemoryRepository（内存存储，适合测试）
     * 2. 记忆窗口：最多保留 20 条消息（避免记忆过载）
     */
    @Primary
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
