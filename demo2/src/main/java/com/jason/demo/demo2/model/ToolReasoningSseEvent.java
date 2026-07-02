package com.jason.demo.demo2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolReasoningSseEvent {

    private String type;
    private String content;
    private String error;
    private String toolName;
    private String innerThought;
    private String confidence;
    private Integer callIndex;

    public static ToolReasoningSseEvent running() {
        return new ToolReasoningSseEvent("RUNNING", null, null, null, null, null, null);
    }

    public static ToolReasoningSseEvent toolReasoning(
            String toolName, String innerThought, String confidence, int callIndex) {
        return new ToolReasoningSseEvent(
                "TOOL_REASONING", null, null, toolName, innerThought, confidence, callIndex);
    }

    public static ToolReasoningSseEvent token(String content) {
        return new ToolReasoningSseEvent("TOKEN", content, null, null, null, null, null);
    }

    public static ToolReasoningSseEvent completed() {
        return new ToolReasoningSseEvent("COMPLETED", null, null, null, null, null, null);
    }

    public static ToolReasoningSseEvent failed(String error) {
        return new ToolReasoningSseEvent("FAILED", null, error, null, null, null, null);
    }
}
