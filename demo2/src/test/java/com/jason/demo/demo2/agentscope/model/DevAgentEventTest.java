package com.jason.demo.demo2.agentscope.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentEventTest {

    @Test
    void factories_setExpectedTypes() {
        assertThat(DevAgentEvent.session("s1").type()).isEqualTo("SESSION");
        assertThat(DevAgentEvent.message("s1", "hi").content()).isEqualTo("hi");
        assertThat(DevAgentEvent.done("s1").type()).isEqualTo("DONE");
        assertThat(DevAgentEvent.error("s1", "boom").type()).isEqualTo("ERROR");
        assertThat(DevAgentEvent.error("s1", "boom").content()).isEqualTo("boom");
    }
}
