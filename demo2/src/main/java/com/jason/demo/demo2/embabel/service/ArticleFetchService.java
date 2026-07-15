package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.QuizAgent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ArticleFetchService {

    public static final int MAX_CONTENT_LENGTH = 12_000;
    private static final int MIN_CONTENT_LENGTH = 20;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    public Optional<String> extractFirstUrl(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = URL_PATTERN.matcher(rawInput);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String url = matcher.group().replaceAll("[)，。,.]+$", "");
        return Optional.of(url);
    }

    public QuizAgent.ArticleInput resolve(String rawInput) {
        String raw = rawInput == null ? "" : rawInput.strip();
        return extractFirstUrl(raw)
                .map(this::fetchArticle)
                .orElseGet(() -> parseLocalArticle(raw));
    }

    public QuizAgent.ArticleInput fetchArticle(String url) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; QuizzardBot/1.0)")
                    .timeout(15_000)
                    .get();
            return toArticleInput(document, url);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch article: " + url, ex);
        }
    }

    /** 单测入口：不发起网络请求。 */
    public QuizAgent.ArticleInput parseHtmlDocument(String html, String sourceUrl) {
        Document document = Jsoup.parse(html, sourceUrl == null ? "" : sourceUrl);
        return toArticleInput(document, sourceUrl);
    }

    public QuizAgent.ArticleInput parseLocalArticle(String rawInput) {
        String title = extractLocalTitle(rawInput);
        String content = extractLocalBody(rawInput);
        content = limitLength(content);
        requireMinContent(content);
        return new QuizAgent.ArticleInput(title, content, null);
    }

    public String limitLength(String content) {
        if (content == null) {
            return "";
        }
        String text = content.strip();
        if (text.length() <= MAX_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CONTENT_LENGTH);
    }

    private QuizAgent.ArticleInput toArticleInput(Document document, String sourceUrl) {
        document.select("script, style, nav, footer, noscript").remove();
        Element root = firstPresent(document.selectFirst("article"),
                document.selectFirst("main"),
                document.body());
        String title = Optional.ofNullable(document.title())
                .filter(t -> !t.isBlank())
                .or(() -> Optional.ofNullable(root)
                        .map(el -> el.selectFirst("h1"))
                        .map(Element::text)
                        .filter(t -> !t.isBlank()))
                .orElse("未命名文章");
        String content = limitLength(root == null ? "" : root.text());
        requireMinContent(content);
        return new QuizAgent.ArticleInput(title, content, sourceUrl);
    }

    private void requireMinContent(String content) {
        if (content == null || content.strip().length() < MIN_CONTENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Article content too short");
        }
    }

    private String extractLocalTitle(String raw) {
        Matcher titled = Pattern.compile("标题[:：]\\s*(.+)").matcher(raw);
        if (titled.find()) {
            return titled.group(1).strip().split("[\\r\\n]")[0].strip();
        }
        Matcher md = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(raw);
        if (md.find()) {
            return md.group(1).strip();
        }
        String firstLine = raw.lines().map(String::strip).filter(s -> !s.isBlank()).findFirst().orElse("未命名文章");
        return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
    }

    private String extractLocalBody(String raw) {
        Matcher body = Pattern.compile("正文[:：]\\s*([\\s\\S]+)").matcher(raw);
        if (body.find()) {
            return body.group(1).strip();
        }
        String withoutUrl = URL_PATTERN.matcher(raw).replaceAll("").strip();
        return withoutUrl;
    }

    private Element firstPresent(Element... elements) {
        for (Element element : elements) {
            if (element != null) {
                return element;
            }
        }
        return null;
    }
}
