package com.jason.demo.demo2.agentscope.model;

import java.util.Map;

public record PendingToolCall(String toolCallId, String name, Map<String, Object> input) {
}
