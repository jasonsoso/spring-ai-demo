package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "推送给前端的澄清问题")
public class AskUserQuestionDto {

    private String header;
    private String question;
    private List<OptionDto> options;
    private Boolean multiSelect;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionDto {
        private String label;
        private String description;
    }
}
