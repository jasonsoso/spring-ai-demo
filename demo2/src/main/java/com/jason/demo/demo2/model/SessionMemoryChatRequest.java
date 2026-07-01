package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Session 记忆对话请求")
public class SessionMemoryChatRequest {

    private String userId;
    private String message;
}
