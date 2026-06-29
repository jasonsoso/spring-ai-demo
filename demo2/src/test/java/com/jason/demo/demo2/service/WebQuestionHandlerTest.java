package com.jason.demo.demo2.service;

import com.jason.demo.demo2.sse.AgentSessionHolder;
import com.jason.demo.demo2.sse.AgentSessionStatus;
import com.jason.demo.demo2.sse.AgentSseSession;
import com.jason.demo.demo2.sse.AgentSseSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebQuestionHandlerTest {

    private AgentSseSessionStore store;
    private WebQuestionHandler handler;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        store = new AgentSseSessionStore(new JsonMapper());
        handler = new WebQuestionHandler(store);
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        AgentSessionHolder.clear();
        executor.shutdownNow();
    }

    @Test
    void handleBlocksUntilAnswerSubmitted() throws Exception {
        AgentSseSession session = store.create("test");

        var question = new AskUserQuestionTool.Question(
                "你更倾向哪种数据库？",
                "数据库类型",
                List.of(
                        new AskUserQuestionTool.Question.Option("PostgreSQL", "关系型"),
                        new AskUserQuestionTool.Question.Option("MongoDB", "文档型")
                ),
                false
        );

        var handleFuture = executor.submit(() -> {
            AgentSessionHolder.setSessionId(session.getSessionId());
            try {
                return handler.handle(List.of(question));
            } finally {
                AgentSessionHolder.clear();
            }
        });

        Thread.sleep(200);
        assertEquals(AgentSessionStatus.AWAITING_INPUT, session.getStatus());
        store.completeAnswer(session.getSessionId(), Map.of("你更倾向哪种数据库？", "PostgreSQL"));

        Map<String, String> answers = handleFuture.get(2, TimeUnit.SECONDS);
        assertEquals("PostgreSQL", answers.get("你更倾向哪种数据库？"));
    }
}
