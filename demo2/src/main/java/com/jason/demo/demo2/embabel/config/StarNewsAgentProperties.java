package com.jason.demo.demo2.embabel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.star-news-agent")
public record StarNewsAgentProperties(Prompts prompts) {

    public record Prompts(String extractStarPerson, String writeup) {
    }
}
