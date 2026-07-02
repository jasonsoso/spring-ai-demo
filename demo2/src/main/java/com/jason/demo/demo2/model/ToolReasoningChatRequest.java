package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "工具推理捕获对话请求")
public class ToolReasoningChatRequest {

    @Schema(description = "会话 ID（多轮记忆键）", example = "demo-session-001")
    private String sessionId;

    @Schema(description = "用户消息", example = "帮我规划北京周末游，先看天气再推荐人文景点")
    private String message;
}
