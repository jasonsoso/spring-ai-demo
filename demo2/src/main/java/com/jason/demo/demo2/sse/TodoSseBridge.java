package com.jason.demo.demo2.sse;

import com.jason.demo.demo2.model.TodoItemDto;
import org.springaicommunity.agent.tools.TodoWriteTool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 TodoWrite 工具回调桥接为 SSE：看板更新 + 每完成一项子任务推送产出片段。
 */
public final class TodoSseBridge {

    private static final ThreadLocal<Map<String, String>> PREVIOUS_STATUSES = ThreadLocal.withInitial(HashMap::new);

    private TodoSseBridge() {
    }

    public static void onTodosUpdated(AgentSseSessionStore sessionStore, String sessionId, TodoWriteTool.Todos todos) {
        List<TodoItemDto> current = toItems(todos);
        sessionStore.pushEvent(sessionId, AgentSseEvent.todos(todos));

        Map<String, String> previous = PREVIOUS_STATUSES.get();
        List<TodoItemDto> newlyCompleted = new ArrayList<>();
        for (TodoItemDto item : current) {
            String prior = previous.get(item.getContent());
            if ("completed".equals(item.getStatus()) && !"completed".equals(prior)) {
                newlyCompleted.add(item);
            }
        }

        if (!newlyCompleted.isEmpty()) {
            String delta = AgentTextAccumulator.consumeNewText();
            TodoItemDto last = newlyCompleted.get(newlyCompleted.size() - 1);
            String content = delta.isBlank()
                    ? "（本步骤已完成，模型未输出额外文本）"
                    : delta;
            int taskIndex = indexOf(current, last) + 1;
            sessionStore.pushEvent(sessionId, AgentSseEvent.taskResult(
                    last.getContent(),
                    taskIndex,
                    current.size(),
                    content
            ));
        }

        Map<String, String> snapshot = new HashMap<>();
        for (TodoItemDto item : current) {
            snapshot.put(item.getContent(), item.getStatus());
        }
        PREVIOUS_STATUSES.set(snapshot);
    }

    public static void clear() {
        PREVIOUS_STATUSES.remove();
    }

    private static List<TodoItemDto> toItems(TodoWriteTool.Todos todos) {
        return todos.todos().stream()
                .map(item -> new TodoItemDto(
                        item.content(),
                        item.status().name().toLowerCase(),
                        item.activeForm()))
                .toList();
    }

    private static int indexOf(List<TodoItemDto> items, TodoItemDto target) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getContent().equals(target.getContent())) {
                return i;
            }
        }
        return items.size() - 1;
    }
}
