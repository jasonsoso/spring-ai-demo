package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "语音对话 SSE 事件")
public class VoiceChatSseEvent {

    public static final String RUNNING = "RUNNING";
    public static final String USER_TEXT = "USER_TEXT";
    public static final String TOKEN = "TOKEN";
    public static final String AUDIO_CHUNK = "AUDIO_CHUNK";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Schema(description = "事件类型")
    private String type;

    @Schema(description = "用户/助手文本")
    private String text;

    @Schema(description = "LLM 流式片段")
    private String response;

    @Schema(description = "Base64 音频数据")
    private String audioBase64;

    @Schema(description = "音频 MIME 类型")
    private String mimeType;

    @Schema(description = "错误信息")
    private String error;

    public static VoiceChatSseEvent running() {
        return VoiceChatSseEvent.builder().type(RUNNING).build();
    }

    public static VoiceChatSseEvent userText(String text) {
        return VoiceChatSseEvent.builder().type(USER_TEXT).text(text).build();
    }

    public static VoiceChatSseEvent token(String response) {
        return VoiceChatSseEvent.builder().type(TOKEN).response(response).build();
    }

    public static VoiceChatSseEvent audioChunk(String audioBase64, String mimeType) {
        return VoiceChatSseEvent.builder().type(AUDIO_CHUNK).audioBase64(audioBase64).mimeType(mimeType).build();
    }

    public static VoiceChatSseEvent completed() {
        return VoiceChatSseEvent.builder().type(COMPLETED).build();
    }

    public static VoiceChatSseEvent failed(String error) {
        return VoiceChatSseEvent.builder().type(FAILED).error(error).build();
    }
}
