package com.jason.demo.demo2.embabel.model;

import java.util.Map;

public record EmbabelSseEvent(String event, Object data) {

    public static EmbabelSseEvent agentSelected(String agentName) {
        return new EmbabelSseEvent("AGENT_SELECTED", Map.of("agentName", agentName));
    }

    public static EmbabelSseEvent actionStart(String action) {
        return new EmbabelSseEvent("ACTION_START", Map.of("action", action));
    }

    public static EmbabelSseEvent actionComplete(String action, String outputType) {
        return new EmbabelSseEvent("ACTION_COMPLETE", Map.of("action", action, "outputType", outputType));
    }

    public static EmbabelSseEvent progress(String text) {
        return new EmbabelSseEvent("PROGRESS", Map.of("text", text));
    }

    public static EmbabelSseEvent result(AgentResponse response) {
        return new EmbabelSseEvent("RESULT", response);
    }

    public static EmbabelSseEvent error(String message) {
        return new EmbabelSseEvent("ERROR", Map.of("message", message == null ? "unknown error" : message));
    }
}
