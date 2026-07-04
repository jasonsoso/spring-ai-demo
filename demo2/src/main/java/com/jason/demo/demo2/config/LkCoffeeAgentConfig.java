package com.jason.demo.demo2.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeAgentConfig {

    @Value("${agent.lkcoffee.chat.model:deepseek-v4-pro}")
    private String lkCoffeeChatModel;

    @Value("${lkcoffee.token:}")
    private String defaultToken;

    public String getLkCoffeeChatModel() {
        return lkCoffeeChatModel;
    }

    public String getDefaultToken() {
        return defaultToken;
    }

    @Bean("lkCoffeeChatMemory")
    public ChatMemory lkCoffeeChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    @Bean("lkCoffeeMessageChatMemoryAdvisor")
    public MessageChatMemoryAdvisor lkCoffeeMessageChatMemoryAdvisor(
            @org.springframework.beans.factory.annotation.Qualifier("lkCoffeeChatMemory") ChatMemory lkCoffeeChatMemory) {
        return MessageChatMemoryAdvisor.builder(lkCoffeeChatMemory).build();
    }
}
