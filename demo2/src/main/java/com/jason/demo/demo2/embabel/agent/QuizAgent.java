package com.jason.demo.demo2.embabel.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.jason.demo.demo2.embabel.config.QuizAgentProperties;
import com.jason.demo.demo2.embabel.service.ArticleFetchService;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Agent(description = "根据技术文章内容生成面向开发者学习复盘的测验题")
public class QuizAgent {

    private final ArticleFetchService articleFetchService;
    private final QuizAgentProperties properties;

    public QuizAgent(ArticleFetchService articleFetchService, QuizAgentProperties properties) {
        this.articleFetchService = articleFetchService;
        this.properties = properties;
    }

    @Action
    public ArticleInput extractArticle(UserInput userInput) {
        return articleFetchService.resolve(userInput.getContent());
    }

    @Action
    public ConceptDigest extractConcepts(ArticleInput article, Ai ai) {
        String prompt = properties.prompts().extractConcepts()
                .replace("{title}", nullToEmpty(article.title()))
                .replace("{content}", nullToEmpty(article.content()));
        return ai.withDefaultLlm().createObject(prompt, ConceptDigest.class);
    }

    @Action
    public QuizDraft generateQuiz(ArticleInput article, ConceptDigest digest, Ai ai) {
        String prompt = properties.prompts().generateQuiz()
                .replace("{title}", nullToEmpty(article.title()))
                .replace("{concepts}", renderConcepts(digest == null ? List.of() : digest.concepts()))
                .replace("{content}", nullToEmpty(article.content()));
        return ai.withDefaultLlm().createObject(prompt, QuizDraft.class);
    }

    @AchievesGoal(description = "生成一套可用于技术文章复盘的测验题")
    @Action
    public QuizPack reviewQuiz(ArticleInput article, QuizDraft draft, Ai ai) {
        String prompt = properties.prompts().reviewQuiz()
                .replace("{title}", nullToEmpty(article.title()))
                .replace("{questions}", renderQuestions(draft == null ? List.of() : draft.questions()));
        return ai.withDefaultLlm().createObject(prompt, QuizPack.class);
    }

    private String renderConcepts(List<ConceptPoint> concepts) {
        if (concepts == null || concepts.isEmpty()) {
            return "(无)";
        }
        return IntStream.range(0, concepts.size())
                .mapToObj(i -> {
                    ConceptPoint c = concepts.get(i);
                    return (i + 1) + ". " + nullToEmpty(c.name()) + " — " + nullToEmpty(c.explanation());
                })
                .collect(Collectors.joining("\n"));
    }

    private String renderQuestions(List<QuizQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return "(无)";
        }
        return IntStream.range(0, questions.size())
                .mapToObj(i -> {
                    QuizQuestion q = questions.get(i);
                    String options = q.options() == null ? "" : String.join(" | ", q.options());
                    return (i + 1) + ". " + nullToEmpty(q.question())
                            + "\n选项: " + options
                            + "\n答案: " + nullToEmpty(q.answer())
                            + "\n解释: " + nullToEmpty(q.explanation());
                })
                .collect(Collectors.joining("\n\n"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

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
