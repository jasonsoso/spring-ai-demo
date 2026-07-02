package com.jason.demo.demo2.model;

import org.springframework.ai.tool.annotation.ToolParam;

public record AgentThinking(
        @ToolParam(description = "调用此工具前的逐步推理：为何选该工具、期望获得什么、如何影响后续规划", required = true)
        String innerThought,
        @ToolParam(description = "置信度：low / medium / high", required = false)
        String confidence
) {
}
