package com.jason.demo.demo.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MySQL 持久化聊天记忆配置：JDBC 存储 + Message/Prompt 两种 Advisor。
 */
@Configuration
public class MysqlMemoryConfig {

    /**
     * 基于 JDBC Repository 的持久化记忆窗口。
     */
    @Bean("mysqlChatMemory")
    public ChatMemory mysqlChatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(30)
                .build();
    }

    /**
     * 角色消息模式，适用于支持多角色对话的模型。
     */
    @Bean("mysqlMessageChatMemoryAdvisor")
    public MessageChatMemoryAdvisor mysqlMessageChatMemoryAdvisor(
            @Qualifier("mysqlChatMemory") ChatMemory mysqlChatMemory) {
        return MessageChatMemoryAdvisor.builder(mysqlChatMemory).build();
    }

    /**
     * Prompt 封装模式，适用于需要把记忆合并进提示词的场景。
     */
    @Bean("mysqlPromptChatMemoryAdvisor")
    public PromptChatMemoryAdvisor mysqlPromptChatMemoryAdvisor(
            @Qualifier("mysqlChatMemory") ChatMemory mysqlChatMemory) {
        return PromptChatMemoryAdvisor.builder(mysqlChatMemory).build();
    }
}
