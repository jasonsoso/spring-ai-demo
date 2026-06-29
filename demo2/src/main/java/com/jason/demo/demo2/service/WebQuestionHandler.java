package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserQuestionDto;
import com.jason.demo.demo2.sse.AgentSessionHolder;
import com.jason.demo.demo2.sse.AgentSessionStatus;
import com.jason.demo.demo2.sse.AgentSseEvent;
import com.jason.demo.demo2.sse.AgentSseSession;
import com.jason.demo.demo2.sse.AgentSseSessionStore;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class WebQuestionHandler implements AskUserQuestionTool.QuestionHandler {

    private final AgentSseSessionStore sessionStore;

    @Override
    public Map<String, String> handle(List<AskUserQuestionTool.Question> questions) {
        String sessionId = AgentSessionHolder.getSessionId();
        if (sessionId == null) {
            throw new IllegalStateException("No agent session bound to current thread");
        }

        AgentSseSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));

        List<AskUserQuestionDto> dtos = questions.stream()
                .map(q -> new AskUserQuestionDto(
                        q.header(),
                        q.question(),
                        q.options().stream()
                                .map(o -> new AskUserQuestionDto.OptionDto(o.label(), o.description()))
                                .collect(Collectors.toList()),
                        q.multiSelect()))
                .collect(Collectors.toList());

        CompletableFuture<Map<String, String>> answerFuture = new CompletableFuture<>();
        session.setAnswerFuture(answerFuture);
        session.setStatus(AgentSessionStatus.AWAITING_INPUT);
        sessionStore.pushEvent(sessionId, AgentSseEvent.questions(dtos));

        return answerFuture.join();
    }
}
