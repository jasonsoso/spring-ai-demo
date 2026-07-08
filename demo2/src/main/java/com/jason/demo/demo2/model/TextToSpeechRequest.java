package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "ElevenLabs TTS 请求")
public record TextToSpeechRequest(
        @Schema(description = "待转换文本", requiredMode = Schema.RequiredMode.REQUIRED)
        String text,
        @Schema(description = "ElevenLabs 语音 ID", example = "21m00Tcm4TlvDq8ikWAM")
        String voiceId,
        @Schema(description = "稳定性 0.0~1.0", example = "0.75")
        Double stability,
        @Schema(description = "相似度增强 0.0~1.0", example = "0.75")
        Double similarityBoost,
        @Schema(description = "风格夸张度 0.0~1.0", example = "0.3")
        Double style,
        @Schema(description = "是否启用扬声器增强", example = "true")
        Boolean useSpeakerBoost,
        @Schema(description = "语速倍率 0.25~4.0", example = "1.0")
        Double speed
) {
}
