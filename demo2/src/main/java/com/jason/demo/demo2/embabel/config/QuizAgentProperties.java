package com.jason.demo.demo2.embabel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.quiz-agent")
public record QuizAgentProperties(Prompts prompts) {

    public record Prompts(String extractConcepts, String generateQuiz, String reviewQuiz) {
    }
}
