package com.jason.demo.demo2.agentscopea2a.server;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
public class RiskReviewServerReadiness {

    private final AgentScopeA2aServer server;
    private final CountDownLatch ready = new CountDownLatch(1);

    public RiskReviewServerReadiness(AgentScopeA2aServer server) {
        this.server = server;
    }

    @EventListener
    @Order(0)
    public void onApplicationEvent(ApplicationReadyEvent event) {
        server.postEndpointReady();
        ready.countDown();
    }

    public void awaitReady(Duration timeout) {
        try {
            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("AgentScope A2A Server is not ready");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for AgentScope A2A Server", ex);
        }
    }

    public boolean isReady() {
        return ready.getCount() == 0;
    }
}
