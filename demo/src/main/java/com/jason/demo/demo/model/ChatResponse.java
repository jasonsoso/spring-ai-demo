package com.jason.demo.demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "聊天响应")
public class ChatResponse {

    @Schema(description = "状态码", example = "200")
    private int code;

    @Schema(description = "状态描述", example = "success")
    private String message;

    @Schema(description = "AI 回复内容")
    private String response;

    public ChatResponse(String response) {
        this.code = 200;
        this.message = "success";
        this.response = response;
    }
}
