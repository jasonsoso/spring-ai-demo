package com.jason.demo.demo2.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AskUserSseEvent {

    private String type;
    private List<AskUserQuestionDto> questions;
    private String response;
    private String error;

    public static AskUserSseEvent running() {
        return new AskUserSseEvent("RUNNING", null, null, null);
    }

    public static AskUserSseEvent questions(List<AskUserQuestionDto> questions) {
        return new AskUserSseEvent("QUESTIONS", questions, null, null);
    }

    public static AskUserSseEvent completed(String response) {
        return new AskUserSseEvent("COMPLETED", null, response, null);
    }

    public static AskUserSseEvent failed(String error) {
        return new AskUserSseEvent("FAILED", null, null, error);
    }
}
