package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "瑞幸 MCP 点单对话请求")
public class LkCoffeeChatRequest {

    @Schema(description = "会话 ID", example = "lk-session-001")
    private String sessionId;

    @Schema(description = "用户消息", example = "帮我来一杯冰美式")
    private String message;

    @Schema(description = "瑞幸 Bearer Token（可选，覆盖环境变量）")
    private String token;

    @Schema(description = "经度（可选，来自浏览器定位或地址解析）")
    private Double longitude;

    @Schema(description = "纬度（可选）")
    private Double latitude;

    @Schema(description = "地址（可选，无经纬度时供 Agent geocode）")
    private String address;
}
