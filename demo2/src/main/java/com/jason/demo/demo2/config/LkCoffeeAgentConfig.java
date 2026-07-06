package com.jason.demo.demo2.config;

import com.jason.demo.demo2.mcp.client.LkCoffeeTokenResolver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeAgentConfig {

    @Value("${agent.lkcoffee.chat.model:deepseek-v4-pro}")
    private String lkCoffeeChatModel;

    private final LkCoffeeTokenResolver tokenResolver;

    public LkCoffeeAgentConfig(LkCoffeeTokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    @PostConstruct
    void logTokenStatus() {
        if (StringUtils.hasText(resolveDefaultToken())) {
            log.info("[LkCoffee] LKCOFFEE_TOKEN 已配置（来源: {}）", tokenSource());
        } else {
            log.warn("[LkCoffee] LKCOFFEE_TOKEN 未配置；请设置环境变量并重启应用");
        }
    }

    public String getLkCoffeeChatModel() {
        return lkCoffeeChatModel;
    }

    /** 启动时注入的 lkcoffee.token，可能为空（进程启动后才设置环境变量时） */
    public String getDefaultToken() {
        return tokenResolver.resolveDefault();
    }

    /** 解析默认 Token：{@code LKCOFFEE_TOKEN} 环境变量 → {@code lkcoffee.token}。 */
    public String resolveDefaultToken() {
        return tokenResolver.resolveDefault();
    }

    private String tokenSource() {
        return switch (tokenResolver.defaultTokenSource()) {
            case ENV -> "LKCOFFEE_TOKEN(运行时环境变量)";
            case PROPERTY -> "lkcoffee.token(启动时)";
            case NONE -> "未知";
        };
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
