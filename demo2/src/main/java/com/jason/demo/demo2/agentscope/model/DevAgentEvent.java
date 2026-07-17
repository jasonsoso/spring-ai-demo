package com.jason.demo.demo2.agentscope.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DevAgentEvent(
        String type,
        String sessionId,
        String content,
        String eventId,
        String toolCallId,
        String name,
        String state) {

    public static DevAgentEvent session(String sessionId) {
        return new DevAgentEvent("SESSION", sessionId, "", null, null, null, null);
    }

    public static DevAgentEvent message(String sessionId, String content) {
        return new DevAgentEvent(
                "MESSAGE", sessionId, content == null ? "" : content, null, null, null, null);
    }

    public static DevAgentEvent done(String sessionId) {
        return new DevAgentEvent("DONE", sessionId, "", null, null, null, null);
    }

    public static DevAgentEvent error(String sessionId, String content) {
        return new DevAgentEvent(
                "ERROR", sessionId, content == null ? "" : content, null, null, null, null);
    }

    public static DevAgentEvent lifecycle(
            String type, String sessionId, String eventId, String content) {
        return new DevAgentEvent(
                type, sessionId, content == null ? "" : content, eventId, null, null, null);
    }

    public static DevAgentEvent toolCallStart(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String content) {
        return new DevAgentEvent(
                "TOOL_CALL_START",
                sessionId,
                content == null ? "" : content,
                eventId,
                toolCallId,
                name,
                null);
    }

    public static DevAgentEvent toolResultEnd(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String state) {
        return new DevAgentEvent(
                "TOOL_RESULT_END", sessionId, "", eventId, toolCallId, name, state);
    }

    public static DevAgentEvent agentResult(String sessionId, String eventId, String content) {
        return new DevAgentEvent(
                "AGENT_RESULT",
                sessionId,
                content == null ? "" : content,
                eventId,
                null,
                null,
                null);
    }
}
