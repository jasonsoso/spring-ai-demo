package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "语音转文字响应")
public class SttTranscribeResponse {

    @Schema(description = "识别文本")
    private String text;

    @Schema(description = "语言代码")
    private String language;
}
