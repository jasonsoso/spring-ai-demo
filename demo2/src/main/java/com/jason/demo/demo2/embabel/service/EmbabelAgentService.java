package com.jason.demo.demo2.embabel.service;

import com.embabel.agent.api.common.autonomy.AgentProcessExecution;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.common.autonomy.NoAgentFound;
import com.embabel.agent.api.common.autonomy.ProcessExecutionException;
import com.embabel.agent.core.ProcessOptions;
import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import com.jason.demo.demo2.embabel.model.AgentResponse;
import com.jason.demo.demo2.embabel.model.EmbabelSseEvent;
import com.jason.demo.demo2.embabel.sse.EmbabelSseBridge;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EmbabelAgentService {

    private final Autonomy autonomy;
    private final EmbabelSseBridge sseBridge;

    public EmbabelAgentService(Autonomy autonomy, EmbabelSseBridge sseBridge) {
        this.autonomy = autonomy;
        this.sseBridge = sseBridge;
    }

    public AgentResponse ask(String message) {
        AgentProcessExecution execution = run(message, ProcessOptions.DEFAULT);
        Object output = execution.getOutput();
        validateOutput(output);
        return toResponse(execution, output);
    }

    public void streamAsk(String message, SseEmitter emitter, JsonMapper jsonMapper) {
        EmbabelSseBridge.RequestBridge bridge = sseBridge.create(emitter, jsonMapper);
        try {
            bridge.send(EmbabelSseEvent.progress("正在分析请求并选择 Agent…"));
            ProcessOptions options = ProcessOptions.DEFAULT.withListener(bridge);
            AgentProcessExecution execution = run(message, options);
            String agentName = execution.getAgentProcess().getAgent().getName();
            bridge.send(EmbabelSseEvent.agentSelected(agentName));
            Object output = execution.getOutput();
            validateOutput(output);
            AgentResponse response = toResponse(execution, output);
            bridge.send(EmbabelSseEvent.result(response));
            emitter.complete();
        } catch (ResponseStatusException ex) {
            bridge.send(EmbabelSseEvent.error(ex.getReason()));
            emitter.completeWithError(ex);
        } catch (Exception ex) {
            bridge.send(EmbabelSseEvent.error(ex.getMessage()));
            emitter.completeWithError(ex);
        }
    }

    private AgentProcessExecution run(String message, ProcessOptions options) {
        try {
            return autonomy.chooseAndRunAgent(message.strip(), options);
        } catch (NoAgentFound ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No matching agent", ex);
        } catch (ProcessExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent execution failed", ex);
        }
    }

    private AgentResponse toResponse(AgentProcessExecution execution, Object output) {
        return new AgentResponse(
                execution.getAgentProcess().getId(),
                execution.getAgentProcess().getAgent().getName(),
                output.getClass().getSimpleName(),
                output);
    }

    public void validateOutput(Object output) {
        if (output instanceof StarNewsAgent.Writeup writeup) {
            requireText(writeup.title(), "Writeup.title");
            requireCompleteSentence(writeup.summary(), "Writeup.summary");
            requireCompleteSentence(writeup.advice(), "Writeup.advice");
            return;
        }
        if (output instanceof PolicyAgent.PolicyAnswer answer) {
            requireText(answer.title(), "PolicyAnswer.title");
            requireCompleteSentence(answer.answer(), "PolicyAnswer.answer");
            requireText(answer.source(), "PolicyAnswer.source");
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unsupported agent output type");
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent returned blank field: " + fieldName);
        }
    }

    private void requireCompleteSentence(String value, String fieldName) {
        requireText(value, fieldName);
        String text = value.strip();
        if (!text.matches(".*[。！？.!?]$")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent returned incomplete field: " + fieldName);
        }
    }
}
