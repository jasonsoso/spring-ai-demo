package com.jason.demo.demo2.config;

import com.jason.demo.demo2.service.WebQuestionHandler;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AskUserAgentConfig {

    @Bean
    public AskUserQuestionTool askUserQuestionTool(WebQuestionHandler webQuestionHandler) {
        return AskUserQuestionTool.builder()
                .questionHandler(webQuestionHandler)
                .build();
    }
}
