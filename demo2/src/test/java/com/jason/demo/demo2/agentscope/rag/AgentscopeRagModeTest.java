package com.jason.demo.demo2.agentscope.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeRagModeTest {

    @Test
    void nullOrBlank_isNone() {
        assertThat(AgentscopeRagMode.from(null)).isEqualTo(AgentscopeRagMode.NONE);
        assertThat(AgentscopeRagMode.from("  ")).isEqualTo(AgentscopeRagMode.NONE);
    }

    @Test
    void parsesCaseInsensitive() {
        assertThat(AgentscopeRagMode.from("generic")).isEqualTo(AgentscopeRagMode.GENERIC);
        assertThat(AgentscopeRagMode.from("AGENTIC")).isEqualTo(AgentscopeRagMode.AGENTIC);
        assertThat(AgentscopeRagMode.from("none")).isEqualTo(AgentscopeRagMode.NONE);
    }

    @Test
    void illegal_isNone() {
        assertThat(AgentscopeRagMode.from("STATIC")).isEqualTo(AgentscopeRagMode.NONE);
    }
}
