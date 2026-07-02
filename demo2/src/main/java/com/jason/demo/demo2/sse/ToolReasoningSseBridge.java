package com.jason.demo.demo2.sse;

import com.jason.demo.demo2.model.AgentThinking;
import com.jason.demo.demo2.model.ToolReasoningSseEvent;

public final class ToolReasoningSseBridge {

    private ToolReasoningSseBridge() {
    }

    public static void onToolReasoning(String toolName, AgentThinking thinking) {
        ToolReasoningStreamContext.currentHolder().ifPresent(ctx -> {
            int callIndex = ctx.callIndex().incrementAndGet();
            String innerThought = thinking != null && thinking.innerThought() != null
                    ? thinking.innerThought() : "（模型未提供推理）";
            String confidence = thinking != null ? thinking.confidence() : null;
            ToolReasoningSseEvent event = ToolReasoningSseEvent.toolReasoning(
                    toolName, innerThought, confidence, callIndex);
            try {
                ctx.sender().accept(ctx.jsonMapper().writeValueAsString(event));
            } catch (Exception e) {
                ctx.emitter().completeWithError(e);
            }
        });
    }
}
