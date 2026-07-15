package com.jason.demo.demo2.embabel.service;

import com.embabel.agent.api.common.autonomy.AgentProcessExecution;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.common.autonomy.NoAgentFound;
import com.embabel.agent.api.common.autonomy.ProcessExecutionException;
import com.embabel.agent.core.ProcessOptions;
import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import com.jason.demo.demo2.embabel.agent.QuizAgent;
import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import com.jason.demo.demo2.embabel.model.AgentResponse;
import com.jason.demo.demo2.embabel.model.EmbabelSseEvent;
import com.jason.demo.demo2.embabel.sse.EmbabelSseBridge;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        if (output instanceof QuizAgent.QuizPack quizPack) {
            requireText(quizPack.title(), "QuizPack.title");
            requireQuestions(quizPack.questions());
            requireCompleteSentence(quizPack.review(), "QuizPack.review");
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

    private void requireQuestions(List<QuizAgent.QuizQuestion> questions) {
        if (questions == null || questions.size() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "QuizPack.questions must contain exactly 3 items");
        }
        for (int i = 0; i < questions.size(); i++) {
            QuizAgent.QuizQuestion q = questions.get(i);
            if (q == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "QuizPack.questions[" + i + "] is null");
            }
            requireText(q.question(), "QuizPack.questions[" + i + "].question");
            List<String> options = q.options();
            if (options == null || options.size() != 4) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "QuizPack.questions[" + i + "].options must contain exactly 4 items");
            }
            for (int j = 0; j < options.size(); j++) {
                requireText(options.get(j), "QuizPack.questions[" + i + "].options[" + j + "]");
            }
            Set<String> unique = new HashSet<>(options);
            if (unique.size() != 4) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "QuizPack.questions[" + i + "].options must be unique");
            }
            requireText(q.answer(), "QuizPack.questions[" + i + "].answer");
            if (!options.contains(q.answer())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "QuizPack.questions[" + i + "].answer must match one option");
            }
            requireCompleteSentence(q.explanation(), "QuizPack.questions[" + i + "].explanation");
        }
    }
}
