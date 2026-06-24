package com.jason.demo.demo.config;

import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 请求响应日志配置：通过 SimpleLoggerAdvisor 全局拦截所有 ChatClient 调用。
 * 日志级别由 application.properties 中 SimpleLoggerAdvisor 的 DEBUG 配置控制。
 */
@Configuration
public class LoggingConfig {

    @Bean
    public SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return SimpleLoggerAdvisor.builder().build();
    }

    /**
     * ChatClientCustomizer 是 Spring AI 自动装配扩展点，
     * 凡是通过 ChatClient.builder(...) 构建的实例都会自动注入此 Advisor，
     * 无需修改任何 Service 代码。
     */
    @Bean
    public ChatClientCustomizer loggingChatClientCustomizer(SimpleLoggerAdvisor simpleLoggerAdvisor) {
        return builder -> builder.defaultAdvisors(simpleLoggerAdvisor);
    }
}
