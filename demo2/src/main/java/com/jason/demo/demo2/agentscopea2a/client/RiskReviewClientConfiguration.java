package com.jason.demo.demo2.agentscopea2a.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RiskReviewClientProperties.class)
public class RiskReviewClientConfiguration {

    @Bean
    RiskReviewTool riskReviewTool(RemoteRiskReviewService service) {
        return new RiskReviewTool(service);
    }
}
