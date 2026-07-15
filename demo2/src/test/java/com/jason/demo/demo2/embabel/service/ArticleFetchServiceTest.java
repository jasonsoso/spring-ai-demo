package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.QuizAgent;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleFetchServiceTest {

    private final ArticleFetchService service = new ArticleFetchService();

    @Test
    void extractFirstUrl_returnsFirstHttpUrl() {
        String raw = "请出题：https://docs.spring.io/spring-ai/reference/api/chatclient.html 谢谢";
        assertThat(service.extractFirstUrl(raw))
                .contains("https://docs.spring.io/spring-ai/reference/api/chatclient.html");
    }

    @Test
    void resolve_parsesTitleAndBodyMarkers() {
        String raw = """
                请根据下面技术文章生成 3 道单选测验题。标题：工具调用不是 Agent。正文：Tool Calling 解决的是模型如何请求外部工具。Agent 更关注任务目标与停止条件。
                """;
        QuizAgent.ArticleInput article = service.resolve(raw);
        assertThat(article.title()).contains("工具调用");
        assertThat(article.content()).contains("Tool Calling");
        assertThat(article.sourceUrl()).isNull();
    }

    @Test
    void resolve_truncatesLongContent() {
        String body = "正文：" + "甲".repeat(ArticleFetchService.MAX_CONTENT_LENGTH + 500);
        QuizAgent.ArticleInput article = service.resolve("标题：长文\n" + body);
        assertThat(article.content().length()).isLessThanOrEqualTo(ArticleFetchService.MAX_CONTENT_LENGTH);
    }

    @Test
    void resolve_rejectsBlankPastedBody() {
        assertThatThrownBy(() -> service.resolve("请出题"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void parseHtmlDocument_prefersArticleElement() {
        String html = """
                <html><body>
                <nav>导航</nav>
                <article><h1>ChatClient</h1><p>Spring AI ChatClient 简介。</p></article>
                <footer>页脚</footer>
                </body></html>
                """;
        QuizAgent.ArticleInput article = service.parseHtmlDocument(html, "https://example.com/doc");
        assertThat(article.title()).containsIgnoringCase("ChatClient");
        assertThat(article.content()).contains("ChatClient");
        assertThat(article.content()).doesNotContain("导航");
        assertThat(article.sourceUrl()).isEqualTo("https://example.com/doc");
    }
}
