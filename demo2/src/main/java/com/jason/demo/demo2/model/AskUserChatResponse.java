package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "AskUserQuestion 对话响应")
public class AskUserChatResponse {

    @Schema(description = "会话 ID")
    private String sessionId;
}
