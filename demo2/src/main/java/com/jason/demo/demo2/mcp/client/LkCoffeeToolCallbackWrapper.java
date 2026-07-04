package com.jason.demo.demo2.mcp.client;

import com.jason.demo.demo2.model.LkCoffeeSseEvent;
import com.jason.demo.demo2.sse.LkCoffeeStreamContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LkCoffeeToolCallbackWrapper implements ToolCallback {

    private static final Pattern QR_URL = Pattern.compile(
            "\"payOrderQrCodeUrl\"\\s*:\\s*\"([^\"]+)\"");

    private final ToolCallback delegate;

    public LkCoffeeToolCallbackWrapper(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        AtomicInteger idx = LkCoffeeStreamContext.callIndex();
        int callIndex = idx != null ? idx.incrementAndGet() : 0;
        String toolName = getToolDefinition().name();
        LkCoffeeStreamContext.emit(LkCoffeeSseEvent.toolCall(toolName, callIndex));

        String result = delegate.call(toolInput);

        String lower = toolName.toLowerCase();
        if (lower.contains("previeworder")) {
            LkCoffeeStreamContext.emit(LkCoffeeSseEvent.orderPreview(result));
        } else if (lower.contains("createorder")) {
            Matcher m = QR_URL.matcher(result);
            if (m.find()) {
                LkCoffeeStreamContext.emit(LkCoffeeSseEvent.paymentQr(m.group(1)));
            }
        }
        return result;
    }
}
