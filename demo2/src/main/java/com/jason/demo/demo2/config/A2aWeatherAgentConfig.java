package com.jason.demo.demo2.config;

import com.jason.demo.demo2.tools.WeatherTool;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springaicommunity.a2a.server.executor.DefaultAgentExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 内嵌 A2A Server：天气专家 Agent（供 A2A 协调器跨协议调用）
 */
@Configuration
public class A2aWeatherAgentConfig {

    @Bean
    public AgentCard a2aWeatherAgentCard(@Value("${server.port:8081}") int port) {
        return new AgentCard.Builder()
                .name("Weather Agent")
                .description("Provides weather information for cities in China")
                .url("http://localhost:" + port + "/")
                .version("1.0.0")
                .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("weather_search")
                        .name("Search weather")
                        .description("Get temperature and conditions for any city")
                        .tags(List.of("weather"))
                        .examples(List.of("What's the weather in Beijing?"))
                        .build()))
                .protocolVersion("0.3.0")
                .build();
    }

    @Bean
    public AgentExecutor a2aWeatherAgentExecutor(ChatClient.Builder chatClientBuilder, WeatherTool weatherTool) {
        ChatClient weatherClient = chatClientBuilder.clone()
                .defaultSystem("你是天气助手。必须使用天气工具查询城市天气，用中文简洁回答。")
                .defaultTools(weatherTool)
                .build();

        return new DefaultAgentExecutor(weatherClient, (chat, requestContext) -> {
            String userMessage = DefaultAgentExecutor.extractTextFromMessage(requestContext.getMessage());
            return chat.prompt().user(userMessage).call().content();
        });
    }
}
