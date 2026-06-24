package com.jason.demo.demo2.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MySQL 持久化聊天记忆配置：JDBC 存储 + MessageChatMemoryAdvisor。
 */
@Configuration
public class MysqlMemoryConfig {

    @Bean("mysqlChatMemory")
    public ChatMemory mysqlChatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(30)
                .build();
    }

    @Bean("mysqlMessageChatMemoryAdvisor")
    public MessageChatMemoryAdvisor mysqlMessageChatMemoryAdvisor(
            @Qualifier("mysqlChatMemory") ChatMemory mysqlChatMemory) {
        return MessageChatMemoryAdvisor.builder(mysqlChatMemory).build();
    }
}
