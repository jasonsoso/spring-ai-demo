package com.jason.demo.demo2.config;

import com.jason.demo.demo2.sse.AgentSessionHolder;
import com.jason.demo.demo2.sse.AgentSseSessionStore;
import com.jason.demo.demo2.sse.TodoSseBridge;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TodoAgentConfig {

    @Bean
    public TodoWriteTool todoWriteTool(AgentSseSessionStore sessionStore) {
        return TodoWriteTool.builder()
                .todoEventHandler(todos -> {
                    String sessionId = AgentSessionHolder.getSessionId();
                    if (sessionId != null) {
                        TodoSseBridge.onTodosUpdated(sessionStore, sessionId, todos);
                    }
                })
                .build();
    }
}
