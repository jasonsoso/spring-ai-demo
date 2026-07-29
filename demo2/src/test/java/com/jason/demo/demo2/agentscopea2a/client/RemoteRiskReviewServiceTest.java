package com.jason.demo.demo2.agentscopea2a.client;

import com.jason.demo.demo2.agentscopea2a.server.RiskReviewServerReadiness;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.mock;

class RemoteRiskReviewServiceTest {

    @Test
    void rejectsReviewBeforeClientInitialization() {
        RemoteRiskReviewService service = new RemoteRiskReviewService(
                new RiskReviewClientProperties(
                        "risk-review-agent",
                        "http://localhost:8081/agentscope-a2a",
                        Duration.ofSeconds(1),
                        100),
                mock(RiskReviewServerReadiness.class));

        StepVerifier.create(service.review("审查 delayMillis"))
                .expectErrorMessage("风险审查服务尚未初始化")
                .verify();
    }

    @Test
    void rejectsOversizedChangeDescriptionBeforeRemoteCall() {
        RemoteRiskReviewService service = new RemoteRiskReviewService(
                new RiskReviewClientProperties(
                        "risk-review-agent",
                        "http://localhost:8081/agentscope-a2a",
                        Duration.ofSeconds(1),
                        5),
                mock(RiskReviewServerReadiness.class));

        StepVerifier.create(service.review("123456"))
                .expectErrorMessage("风险审查改动说明超过长度限制")
                .verify();
    }
}
