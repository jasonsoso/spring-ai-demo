package com.jason.demo.demo2.agentscopea2a.client;

import com.jason.demo.demo2.agentscopea2a.server.RiskReviewServerReadiness;
import io.a2a.client.config.ClientConfig;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.A2aAgentConfig;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class RemoteRiskReviewService {

    private static final Logger log = LoggerFactory.getLogger(RemoteRiskReviewService.class);

    private final RiskReviewClientProperties properties;
    private final RiskReviewServerReadiness serverReadiness;
    private final AtomicReference<A2aAgent> remoteAgent = new AtomicReference<>();

    public RemoteRiskReviewService(
            RiskReviewClientProperties properties,
            RiskReviewServerReadiness serverReadiness) {
        this.properties = properties;
        this.serverReadiness = serverReadiness;
    }

    @EventListener
    @Order(1)
    public void startAfterServerReady(ApplicationReadyEvent event) {
        serverReadiness.awaitReady(properties.timeout());
        String cardBaseUrl = normalizeBaseUrl(properties.baseUrl());
        A2aAgent agent = A2aAgent.builder()
                .name(properties.name())
                .agentCardResolver(
                        WellKnownAgentCardResolver.builder()
                                .baseUrl(cardBaseUrl)
                                // Must be relative (no leading '/'); absolute "/.well-known/..."
                                // drops the /agentscope-a2a prefix via URI.resolve.
                                .relativeCardPath(".well-known/agent-card.json")
                                .build())
                .a2aAgentConfig(
                        A2aAgentConfig.builder()
                                .clientConfig(
                                        ClientConfig.builder()
                                                .setStreaming(false)
                                                .build())
                                .build())
                .build();
        remoteAgent.compareAndSet(null, agent);
    }

    public boolean isReady() {
        return remoteAgent.get() != null;
    }

    public Mono<String> review(String message) {
        return Mono.defer(() -> {
            if (message == null || message.isBlank()) {
                return Mono.error(new IllegalArgumentException("风险审查改动说明不能为空"));
            }
            if (message.length() > properties.maxMessageLength()) {
                return Mono.error(new IllegalArgumentException("风险审查改动说明超过长度限制"));
            }
            A2aAgent agent = remoteAgent.get();
            if (agent == null) {
                return Mono.error(new IllegalStateException("风险审查服务尚未初始化"));
            }
            String requestMessage = message.strip();
            log.info("[A2A Client] request baseUrl={}, agent={}, message={}",
                    properties.baseUrl(), properties.name(), requestMessage);
            return agent.call(requestMessage)
                    .timeout(properties.timeout())
                    .map(msg -> msg.getTextContent())
                    .doOnNext(result -> log.info(
                            "[A2A Client] response agent={}, result={}",
                            properties.name(), result))
                    .doOnError(error -> log.error(
                            "[A2A Client] request failed agent={}, message={}",
                            properties.name(), requestMessage, error));
        });
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }
}
