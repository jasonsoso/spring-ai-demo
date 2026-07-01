package com.jason.demo.demo2.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.tool.SessionEventTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionMemoryAgentConfig {

    @Value("${agent.session-memory.compaction.turn-threshold:15}")
    private int turnThreshold;

    @Value("${agent.session-memory.compaction.max-events-to-keep:10}")
    private int maxEventsToKeep;

    @Value("${agent.session-memory.compaction.overlap-size:2}")
    private int overlapSize;

    @Value("${agent.session-memory.chat.model:deepseek-v4-pro}")
    private String sessionChatModel;

    public String getSessionChatModel() {
        return sessionChatModel;
    }

    @Bean
    public ChatClient sessionSummarizationChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone()
                .defaultOptions(DeepSeekChatOptions.builder().model(sessionChatModel))
                .build();
    }

    @Bean
    public SessionMemoryAdvisor sessionMemoryAdvisor(
            SessionService sessionService,
            ChatClient sessionSummarizationChatClient) {
        return SessionMemoryAdvisor.builder(sessionService)
                .compactionTrigger(new TurnCountTrigger(turnThreshold))
                .compactionStrategy(
                        RecursiveSummarizationCompactionStrategy.builder(sessionSummarizationChatClient)
                                .maxEventsToKeep(maxEventsToKeep)
                                .overlapSize(overlapSize)
                                .build())
                .build();
    }

    @Bean
    public SessionEventTools sessionEventTools(SessionService sessionService) {
        return SessionEventTools.builder(sessionService).build();
    }
}
