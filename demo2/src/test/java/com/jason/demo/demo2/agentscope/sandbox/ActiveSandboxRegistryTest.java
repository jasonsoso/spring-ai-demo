package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveSandboxRegistryTest {

    @Test
    void findsByExactAndPrefixedSessionId() {
        ActiveSandboxRegistry registry = new ActiveSandboxRegistry();
        Sandbox sandbox = mock(Sandbox.class);
        SandboxState state = mock(SandboxState.class);
        when(sandbox.getState()).thenReturn(state);
        when(state.getSessionId()).thenReturn("sandbox/session/plan-session-017");

        registry.register(sandbox);

        assertThat(registry.findByAppSessionId("plan-session-017")).contains(sandbox);
        assertThat(registry.findByAppSessionId("sandbox/session/plan-session-017"))
                .contains(sandbox);

        registry.unregister(sandbox);
        assertThat(registry.findByAppSessionId("plan-session-017")).isEmpty();
    }

    @Test
    void fallsBackToLatestWhenSessionIdAssignedLate() {
        ActiveSandboxRegistry registry = new ActiveSandboxRegistry();
        Sandbox sandbox = mock(Sandbox.class);
        SandboxState state = mock(SandboxState.class);
        when(sandbox.getState()).thenReturn(state);
        when(state.getSessionId()).thenReturn(null);

        registry.register(sandbox);
        assertThat(registry.findByAppSessionId("plan-session-018")).contains(sandbox);

        when(state.getSessionId()).thenReturn("sandbox/session/plan-session-018");
        assertThat(registry.findByAppSessionId("plan-session-018")).contains(sandbox);
        assertThat(registry.findByAppSessionId("other-session")).contains(sandbox);
    }
}
