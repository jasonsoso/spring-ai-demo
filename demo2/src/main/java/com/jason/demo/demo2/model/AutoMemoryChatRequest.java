package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "AutoMemory 对话请求")
public class AutoMemoryChatRequest {

    @Schema(description = "用户唯一标识", example = "1001")
    private String userId;

    @Schema(description = "用户消息", example = "周末两天杭州游，2人，偏好西湖人文景点，素食，地铁出行")
    private String message;
}
