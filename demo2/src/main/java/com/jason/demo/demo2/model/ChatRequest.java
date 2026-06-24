package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "聊天请求")
public class ChatRequest {

    @Schema(description = "用户消息内容", example = "你好，请介绍一下露营安全注意事项")
    private String message;
}
