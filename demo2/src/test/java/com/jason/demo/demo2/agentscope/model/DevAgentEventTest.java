package com.jason.demo.demo2.agentscope.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentEventTest {

    @Test
    void legacyFactories_keepNullOptionalFields() {
        assertThat(DevAgentEvent.session("s1"))
                .isEqualTo(new DevAgentEvent(DevAgentEventType.SESSION, "s1", "", null, null, null, null));
        assertThat(DevAgentEvent.message("s1", "hi").content()).isEqualTo("hi");
        assertThat(DevAgentEvent.done("s1").type()).isEqualTo(DevAgentEventType.DONE);
        assertThat(DevAgentEvent.error("s1", "boom").content()).isEqualTo("boom");
        assertThat(DevAgentEvent.message("s1", null).content()).isEqualTo("");
    }

    @Test
    void toolFactories_fillToolFields() {
        DevAgentEvent start = DevAgentEvent.toolCallStart(
                "s1", "e1", "call-1", "read_pom", "准备调用工具：read_pom");
        assertThat(start.type()).isEqualTo(DevAgentEventType.TOOL_CALL_START);
        assertThat(start.toolCallId()).isEqualTo("call-1");
        assertThat(start.name()).isEqualTo("read_pom");
        assertThat(start.state()).isNull();

        DevAgentEvent end = DevAgentEvent.toolResultEnd(
                "s1", "e2", "call-1", "read_pom", "SUCCESS");
        assertThat(end.type()).isEqualTo(DevAgentEventType.TOOL_RESULT_END);
        assertThat(end.state()).isEqualTo("SUCCESS");
        assertThat(end.content()).isEqualTo("");
    }

    @Test
    void agentResult_and_lifecycle() {
        assertThat(DevAgentEvent.agentResult("s1", "e3", "完整回答").type())
                .isEqualTo(DevAgentEventType.AGENT_RESULT);
        assertThat(DevAgentEvent.lifecycle(DevAgentEventType.AGENT_START, "s1", "e0", "Agent 开始").type())
                .isEqualTo(DevAgentEventType.AGENT_START);
    }
}
