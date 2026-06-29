package com.jason.demo.demo2.sse;

/**
 * 收集 Agent 多轮工具调用过程中各轮 Assistant 文本，供 SSE 最终展示完整回复。
 */
public final class AgentTextAccumulator {

    private static final ThreadLocal<StringBuilder> TEXT = ThreadLocal.withInitial(StringBuilder::new);
    private static final ThreadLocal<Integer> CONSUMED_LENGTH = ThreadLocal.withInitial(() -> 0);

    private AgentTextAccumulator() {
    }

    public static void clear() {
        TEXT.remove();
        CONSUMED_LENGTH.remove();
    }

    public static void append(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        StringBuilder sb = TEXT.get();
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(text.strip());
    }

    public static String getText() {
        return TEXT.get().toString();
    }

    /** 返回自上次消费以来新增的 Assistant 文本（用于按子任务推送 SSE）。 */
    public static String consumeNewText() {
        String full = getText();
        int consumed = CONSUMED_LENGTH.get();
        if (full.length() <= consumed) {
            return "";
        }
        String delta = full.substring(consumed);
        CONSUMED_LENGTH.set(full.length());
        return delta;
    }
}
