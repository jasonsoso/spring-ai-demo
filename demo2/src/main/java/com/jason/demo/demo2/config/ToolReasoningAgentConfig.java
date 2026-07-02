package com.jason.demo.demo2.config;

import com.jason.demo.demo2.model.AgentThinking;
import com.jason.demo.demo2.sse.ToolReasoningSseBridge;
import com.jason.demo.demo2.tools.AttractionTool;
import com.jason.demo.demo2.tools.WeatherTool;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolReasoningAgentConfig {

    @Value("${agent.tool-reasoning.chat.model:deepseek-chat}")
    private String toolReasoningChatModel;

    public String getToolReasoningChatModel() {
        return toolReasoningChatModel;
    }

    @Bean
    public MessageChatMemoryAdvisor toolReasoningMessageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    public AugmentedToolCallbackProvider<AgentThinking> toolReasoningProvider(
            WeatherTool weatherTool,
            AttractionTool attractionTool) {
        return AugmentedToolCallbackProvider.<AgentThinking>builder()
                .delegate(MethodToolCallbackProvider.builder()
                        .toolObjects(weatherTool, attractionTool)
                        .build())
                .argumentType(AgentThinking.class)
                .argumentConsumer(event -> ToolReasoningSseBridge.onToolReasoning(
                        event.toolDefinition().name(), event.arguments()))
                .removeExtraArgumentsAfterProcessing(true)
                .build();
    }
}
