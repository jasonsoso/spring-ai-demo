package com.jason.demo.demo2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionMemorySseEvent {

    private String type;
    private String content;
    private String error;

    public static SessionMemorySseEvent token(String content) {
        return new SessionMemorySseEvent("TOKEN", content, null);
    }

    public static SessionMemorySseEvent completed() {
        return new SessionMemorySseEvent("COMPLETED", null, null);
    }

    public static SessionMemorySseEvent failed(String error) {
        return new SessionMemorySseEvent("FAILED", null, error);
    }
}
