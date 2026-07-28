package com.jason.demo.demo2.agentscope.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DevAgentEvent(
        DevAgentEventType type,
        String sessionId,
        String source,
        String content,
        String eventId,
        String toolCallId,
        String name,
        String state,
        List<PendingToolCall> pendingToolCalls,
        String requestId,
        String traceId,
        String spanId) {

    public static DevAgentEvent session(String sessionId) {
        return new DevAgentEvent(
                DevAgentEventType.SESSION,
                sessionId,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent requestContext(
            String sessionId, String requestId, String traceId, String spanId) {
        return new DevAgentEvent(
                DevAgentEventType.REQUEST_CONTEXT,
                sessionId,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                requestId,
                traceId,
                spanId);
    }

    public static DevAgentEvent message(String sessionId, String content) {
        return message(sessionId, null, content);
    }

    public static DevAgentEvent message(String sessionId, String source, String content) {
        return new DevAgentEvent(
                DevAgentEventType.MESSAGE,
                sessionId,
                source,
                content == null ? "" : content,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent done(String sessionId) {
        return new DevAgentEvent(
                DevAgentEventType.DONE,
                sessionId,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent error(String sessionId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.ERROR,
                sessionId,
                null,
                content == null ? "" : content,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent lifecycle(
            DevAgentEventType type, String sessionId, String eventId, String content) {
        return lifecycle(type, sessionId, null, eventId, content);
    }

    public static DevAgentEvent lifecycle(
            DevAgentEventType type,
            String sessionId,
            String source,
            String eventId,
            String content) {
        return new DevAgentEvent(
                type,
                sessionId,
                source,
                content == null ? "" : content,
                eventId,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent toolCallStart(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String content) {
        return toolCallStart(sessionId, null, eventId, toolCallId, name, content);
    }

    public static DevAgentEvent toolCallStart(
            String sessionId,
            String source,
            String eventId,
            String toolCallId,
            String name,
            String content) {
        return new DevAgentEvent(
                DevAgentEventType.TOOL_CALL_START,
                sessionId,
                source,
                content == null ? "" : content,
                eventId,
                toolCallId,
                name,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent toolResultEnd(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String state) {
        return toolResultEnd(sessionId, null, eventId, toolCallId, name, state);
    }

    public static DevAgentEvent toolResultEnd(
            String sessionId,
            String source,
            String eventId,
            String toolCallId,
            String name,
            String state) {
        return new DevAgentEvent(
                DevAgentEventType.TOOL_RESULT_END,
                sessionId,
                source,
                "",
                eventId,
                toolCallId,
                name,
                state,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent agentResult(String sessionId, String eventId, String content) {
        return agentResult(sessionId, null, eventId, content);
    }

    public static DevAgentEvent agentResult(
            String sessionId, String source, String eventId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.AGENT_RESULT,
                sessionId,
                source,
                content == null ? "" : content,
                eventId,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent confirmation(
            String sessionId, String eventId, List<PendingToolCall> pendingToolCalls) {
        return confirmation(sessionId, null, eventId, pendingToolCalls);
    }

    public static DevAgentEvent confirmation(
            String sessionId,
            String source,
            String eventId,
            List<PendingToolCall> pendingToolCalls) {
        return new DevAgentEvent(
                DevAgentEventType.REQUIRE_USER_CONFIRM,
                sessionId,
                source,
                "请确认待执行的工具调用。",
                eventId,
                null,
                null,
                null,
                pendingToolCalls,
                null,
                null,
                null);
    }

    public static DevAgentEvent requestStop(String sessionId, String eventId, String content) {
        return requestStop(sessionId, null, eventId, content);
    }

    public static DevAgentEvent requestStop(
            String sessionId, String source, String eventId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.REQUEST_STOP,
                sessionId,
                source,
                content == null ? "" : content,
                eventId,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent compaction(String sessionId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.COMPACTION,
                sessionId,
                null,
                content == null ? "" : content,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent workspaceDiff(String sessionId, WorkspaceDiff diff) {
        return new DevAgentEvent(
                DevAgentEventType.WORKSPACE_DIFF,
                sessionId,
                null,
                diff.unifiedDiff(),
                diff.diffId(),
                null,
                null,
                null,
                List.of(new PendingToolCall(
                        diff.diffId(),
                        "apply-diff",
                        java.util.Map.of(
                                "diffId", diff.diffId(),
                                "files", diff.files()))),
                null,
                null,
                null);
    }
}
