package com.jason.demo.demo2.agentscope.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentEventTest {

    @Test
    void legacyFactories_keepNullOptionalFields() {
        assertThat(DevAgentEvent.session("s1"))
                .isEqualTo(new DevAgentEvent(
                        DevAgentEventType.SESSION, "s1", "", null, null, null, null, null));
        assertThat(DevAgentEvent.message("s1", "hi").content()).isEqualTo("hi");
        assertThat(DevAgentEvent.done("s1").type()).isEqualTo(DevAgentEventType.DONE);
        assertThat(DevAgentEvent.error("s1", "boom").content()).isEqualTo("boom");
        assertThat(DevAgentEvent.message("s1", null).content()).isEqualTo("");
        assertThat(DevAgentEvent.session("s1").pendingToolCalls()).isNull();
    }

    @Test
    void toolFactories_fillToolFields() {
        DevAgentEvent start = DevAgentEvent.toolCallStart(
                "s1", "e1", "call-1", "read_pom", "准备调用工具：read_pom");
        assertThat(start.type()).isEqualTo(DevAgentEventType.TOOL_CALL_START);
        assertThat(start.toolCallId()).isEqualTo("call-1");
        assertThat(start.name()).isEqualTo("read_pom");
        assertThat(start.state()).isNull();
        assertThat(start.pendingToolCalls()).isNull();

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

    @Test
    void confirmation_carriesPendingToolCalls() {
        PendingToolCall pending = new PendingToolCall(
                "call-9",
                "request_file_change",
                Map.of(
                        "operation", "create",
                        "path", "notes/permission-demo.txt",
                        "content", "hello"));
        DevAgentEvent event = DevAgentEvent.confirmation("s1", "e-c", List.of(pending));
        assertThat(event.type()).isEqualTo(DevAgentEventType.REQUIRE_USER_CONFIRM);
        assertThat(event.content()).isEqualTo("请确认待执行的工具调用。");
        assertThat(event.pendingToolCalls()).containsExactly(pending);
        assertThat(event.eventId()).isEqualTo("e-c");
    }

    @Test
    void requestStop_setsTypeAndContent() {
        DevAgentEvent stop = DevAgentEvent.requestStop("s1", "e-s", "PERMISSION_ASKING");
        assertThat(stop.type()).isEqualTo(DevAgentEventType.REQUEST_STOP);
        assertThat(stop.content()).isEqualTo("PERMISSION_ASKING");
        assertThat(stop.pendingToolCalls()).isNull();
    }

    @Test
    void compaction_setsTypeAndContent() {
        DevAgentEvent event = DevAgentEvent.compaction(
                "s1", "上下文已压缩：7 条 → 1 条摘要 + 2 条原文（共 3 条）");
        assertThat(event.type()).isEqualTo(DevAgentEventType.COMPACTION);
        assertThat(event.sessionId()).isEqualTo("s1");
        assertThat(event.content()).contains("7 条").contains("共 3 条");
        assertThat(event.pendingToolCalls()).isNull();
    }
}
