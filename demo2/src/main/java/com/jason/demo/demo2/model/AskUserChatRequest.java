package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "AskUserQuestion 对话请求")
public class AskUserChatRequest {

    @Schema(description = "用户消息", example = "帮我选一个数据库")
    private String message;
}
