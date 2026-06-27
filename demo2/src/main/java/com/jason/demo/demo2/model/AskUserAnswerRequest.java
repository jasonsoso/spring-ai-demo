package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "AskUserQuestion 答案提交")
public class AskUserAnswerRequest {

    @Schema(description = "会话 ID")
    private String sessionId;

    @Schema(description = "答案 Map，key 为 question 文本")
    private Map<String, String> answers;
}
