package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "AskUserQuestion 答案受理响应")
public class AskUserAnswerResponse {

    @Schema(description = "受理状态", example = "accepted")
    private String status;
}
