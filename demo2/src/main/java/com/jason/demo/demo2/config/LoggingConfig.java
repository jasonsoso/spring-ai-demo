package com.jason.demo.demo2.config;

import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 请求响应日志：
 * <ul>
 *   <li>业务 ChatClient：SimpleLoggerAdvisor（经 ChatClientBuilderCustomizer）</li>
 *   <li>Embabel：LoggingChatModel 包装 ChatModel（见 EmbabelLlmModelFixConfig）</li>
 *   <li>AgentScope：LoggingAgentscopeModel 包装 Model（见 AgentScopeConfig）</li>
 * </ul>
 * 说明：Spring AI 2.0 已废弃 {@code ChatClientCustomizer}（forRemoval），
 * 且 Embabel 主路径经 {@code SpringAiLlmMessageSender} 直接 {@code ChatModel.call()}，
 * 不会进入 ChatClient Advisor 链，故 Embabel 不能依赖 Advisor 打日志。
 * AgentScope 使用自有 {@code io.agentscope.core.model.Model}，同样不经 Advisor。
 */
@Configuration
public class LoggingConfig {

    @Bean
    public SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return SimpleLoggerAdvisor.builder().build();
    }

    /**
     * 业务 ChatClient.Builder 自动注入此 Advisor。
     */
    @Bean
    public ChatClientBuilderCustomizer loggingChatClientCustomizer(SimpleLoggerAdvisor simpleLoggerAdvisor) {
        return builder -> builder.defaultAdvisors(simpleLoggerAdvisor);
    }
}
