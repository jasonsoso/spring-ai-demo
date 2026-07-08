package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "语音对话流式请求")
public class VoiceChatRequest {

    @Schema(description = "用户消息", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "ElevenLabs 语音 ID，空则使用配置默认")
    private String voiceId;

    @Schema(description = "是否自动朗读", defaultValue = "true")
    private Boolean autoSpeak = true;
}
