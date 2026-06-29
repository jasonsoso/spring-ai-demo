package com.jason.demo.demo2.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jason.demo.demo2.model.AskUserQuestionDto;
import com.jason.demo.demo2.model.TodoItemDto;
import com.jason.demo.demo2.model.TodoProgressDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springaicommunity.agent.tools.TodoWriteTool;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentSseEvent {

    private String type;
    private List<AskUserQuestionDto> questions;
    private List<TodoItemDto> todos;
    private TodoProgressDto progress;
    private String response;
    private String error;
    private String taskName;
    private Integer taskIndex;
    private Integer totalTasks;

    public static AgentSseEvent running() {
        return new AgentSseEvent("RUNNING", null, null, null, null, null, null, null, null);
    }

    public static AgentSseEvent questions(List<AskUserQuestionDto> questions) {
        return new AgentSseEvent("QUESTIONS", questions, null, null, null, null, null, null, null);
    }

    public static AgentSseEvent todos(TodoWriteTool.Todos todos) {
        List<TodoItemDto> items = todos.todos().stream()
                .map(item -> new TodoItemDto(
                        item.content(),
                        item.status().name().toLowerCase(),
                        item.activeForm()))
                .toList();
        int completed = (int) items.stream()
                .filter(t -> "completed".equals(t.getStatus()))
                .count();
        int total = items.size();
        int percent = total == 0 ? 0 : (int) (completed * 100.0 / total);
        return new AgentSseEvent("TODOS", null, items, new TodoProgressDto(completed, total, percent), null, null, null, null, null);
    }

    public static AgentSseEvent taskResult(String taskName, int taskIndex, int totalTasks, String content) {
        return new AgentSseEvent("TASK_RESULT", null, null, null, content, null, taskName, taskIndex, totalTasks);
    }

    public static AgentSseEvent completed(String response) {
        return new AgentSseEvent("COMPLETED", null, null, null, response, null, null, null, null);
    }

    public static AgentSseEvent failed(String error) {
        return new AgentSseEvent("FAILED", null, null, null, null, error, null, null, null);
    }
}
