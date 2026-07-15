package com.jason.demo.demo2.embabel.agent;

import java.util.List;

/**
 * Quizzard — 根据技术文章生成测验题。Action 实现见后续任务。
 */
public class QuizAgent {

    public record ArticleInput(String title, String content, String sourceUrl) {
    }

    public record ConceptPoint(String name, String explanation) {
    }

    public record ConceptDigest(List<ConceptPoint> concepts) {
    }

    public record QuizQuestion(
            String question,
            List<String> options,
            String answer,
            String explanation) {
    }

    public record QuizDraft(List<QuizQuestion> questions) {
    }

    public record QuizPack(String title, List<QuizQuestion> questions, String review) {
    }
}
