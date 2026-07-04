package com.jason.demo.demo2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LkCoffeeSseEvent {

    private String type;
    private String content;
    private String error;
    private String toolName;
    private Integer callIndex;
    private Object payload;
    private String qrUrl;

    public static LkCoffeeSseEvent running() {
        return new LkCoffeeSseEvent("RUNNING", null, null, null, null, null, null);
    }

    public static LkCoffeeSseEvent toolCall(String toolName, int callIndex) {
        return new LkCoffeeSseEvent("TOOL_CALL", null, null, toolName, callIndex, null, null);
    }

    public static LkCoffeeSseEvent token(String content) {
        return new LkCoffeeSseEvent("TOKEN", content, null, null, null, null, null);
    }

    public static LkCoffeeSseEvent orderPreview(Object payload) {
        return new LkCoffeeSseEvent("ORDER_PREVIEW", null, null, null, null, payload, null);
    }

    public static LkCoffeeSseEvent paymentQr(String qrUrl) {
        return new LkCoffeeSseEvent("PAYMENT_QR", null, null, null, null, null, qrUrl);
    }

    public static LkCoffeeSseEvent completed() {
        return new LkCoffeeSseEvent("COMPLETED", null, null, null, null, null, null);
    }

    public static LkCoffeeSseEvent failed(String error) {
        return new LkCoffeeSseEvent("FAILED", null, error, null, null, null, null);
    }
}
