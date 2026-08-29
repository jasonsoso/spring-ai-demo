package com.jason.demo.demo2.lock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockKeysTest {

    @Test
    void messageHash_isStableAndShort() {
        String h1 = LockKeys.messageHash("hello");
        String h2 = LockKeys.messageHash("hello");
        assertThat(h1).isEqualTo(h2).hasSize(16);
        assertThat(LockKeys.messageHash("hello!")).isNotEqualTo(h1);
    }

    @Test
    void demoSubmitKey_usesNormalizedParts() {
        assertThat(LockKeys.demoSubmitKey("u1", "s1", "m"))
                .isEqualTo("demo:lock:submit:u1:s1:" + LockKeys.messageHash("m"));
    }

    @Test
    void devAgentAskKey_usesAskPrefix() {
        assertThat(LockKeys.devAgentAskKey("u1", "s1", "m"))
                .startsWith("agentscope:dev-agent:ask:u1:s1:");
    }

    @Test
    void delayScannerFallbackKey_isStable() {
        assertThat(LockKeys.delayScannerFallbackKey()).isEqualTo("delay:scanner:fallback");
    }

    @Test
    void stockReconcileKey_isStable() {
        assertThat(LockKeys.stockReconcileKey()).isEqualTo("product:stock:reconcile");
    }
}
