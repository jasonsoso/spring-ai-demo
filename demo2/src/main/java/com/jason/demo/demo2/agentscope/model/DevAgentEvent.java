package com.jason.demo.demo2.agentscope.model;

public record DevAgentEvent(String type, String sessionId, String content) {

    public static DevAgentEvent session(String sessionId) {
        return new DevAgentEvent("SESSION", sessionId, "");
    }

    public static DevAgentEvent message(String sessionId, String content) {
        return new DevAgentEvent("MESSAGE", sessionId, content == null ? "" : content);
    }

    public static DevAgentEvent done(String sessionId) {
        return new DevAgentEvent("DONE", sessionId, "");
    }

    public static DevAgentEvent error(String sessionId, String content) {
        return new DevAgentEvent("ERROR", sessionId, content == null ? "" : content);
    }
}
