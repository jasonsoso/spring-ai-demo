package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import com.jason.demo.demo2.embabel.agent.QuizAgent;
import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbabelAgentServiceTest {

    private final EmbabelAgentService service = new EmbabelAgentService(null, null);

    @Test
    void validateOutput_rejectsBlankWriteupTitle() {
        var bad = new StarNewsAgent.Writeup("", "完整句子。", "建议完整。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validateOutput_acceptsValidPolicyAnswer() {
        var ok = new PolicyAgent.PolicyAnswer("标题", "这是完整回答。", "来源");
        assertThatCode(() -> service.validateOutput(ok)).doesNotThrowAnyException();
    }

    @Test
    void validateOutput_acceptsValidQuizPack() {
        var ok = sampleQuizPack();
        assertThatCode(() -> service.validateOutput(ok)).doesNotThrowAnyException();
    }

    @Test
    void validateOutput_rejectsWrongOptionCount() {
        var badQ = new QuizAgent.QuizQuestion("题干？", List.of("A1", "A2", "A3"), "A1", "解释完整。");
        var bad = new QuizAgent.QuizPack("标题", List.of(badQ, badQ, badQ), "这套题考察核心概念。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void validateOutput_rejectsAnswerNotInOptions() {
        var q = new QuizAgent.QuizQuestion(
                "题干？",
                List.of("甲", "乙", "丙", "丁"),
                "戊",
                "解释完整。");
        var bad = new QuizAgent.QuizPack("标题", List.of(q, q, q), "这套题考察核心概念。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validateOutput_rejectsWrongQuestionCount() {
        var q = new QuizAgent.QuizQuestion(
                "题干？",
                List.of("甲", "乙", "丙", "丁"),
                "甲",
                "解释完整。");
        var bad = new QuizAgent.QuizPack("标题", List.of(q), "这套题考察核心概念。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static QuizAgent.QuizPack sampleQuizPack() {
        var q1 = new QuizAgent.QuizQuestion(
                "Tool Calling 与 Agent 的根本区别？",
                List.of("多工具", "单次交互 vs 目标状态循环", "仅 API", "无错误处理"),
                "单次交互 vs 目标状态循环",
                "文章指出 Agent 关注目标、状态与停止条件。");
        var q2 = new QuizAgent.QuizQuestion(
                "为什么先抽知识点？",
                List.of("好看", "显式化出题依据", "省 token", "换模型"),
                "显式化出题依据",
                "中间对象便于排查题目是否偏题。");
        var q3 = new QuizAgent.QuizQuestion(
                "answer 字段应如何表示？",
                List.of("只写 A", "与某 option 全文一致", "任意文字", "留空"),
                "与某 option 全文一致",
                "便于校验答案是否落在选项内。");
        return new QuizAgent.QuizPack("测验标题", List.of(q1, q2, q3), "这套题主要考察 Agent 拆分与校验要点。");
    }
}
