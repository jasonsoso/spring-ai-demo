package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.ProductAnalysis;
import com.jason.demo.demo2.model.TechStack;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结构化输出：Spring AI 2.0 自动将模型 JSON 映射为 Java 对象
 */
@Tag(name = "结构化输出", description = "告别手动解析 JSON，直接返回 Java 对象")
@RestController
@RequestMapping("/ai/structured")
public class StructuredOutputController {

    private final ChatClient chatClient;

    public StructuredOutputController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Operation(summary = "产品分析（单对象）",
            description = "分析指定产品，返回优缺点、评分与购买建议，底层自动完成 JSON 解析")
    @GetMapping("/analyze")
    public ProductAnalysis analyzeProduct(
            @Parameter(description = "产品名称", example = "MacBook Air M4")
            @RequestParam String productName) {
        return chatClient.prompt()
                .user("分析产品：" + productName + "，给出优缺点和推荐指数（score 为 1-10 的整数）")
                .call()
                .entity(ProductAnalysis.class);
    }

    @Operation(summary = "技术栈推荐（列表）",
            description = "为指定场景推荐 5 个技术栈，返回结构化列表")
    @GetMapping("/tech-stacks")
    public List<TechStack> recommendTechStacks(
            @Parameter(description = "业务场景", example = "高并发电商秒杀系统")
            @RequestParam String scenario) {
        return chatClient.prompt()
                .user("为以下场景推荐 5 个技术栈：" + scenario)
                .call()
                .entity(new ParameterizedTypeReference<List<TechStack>>() {});
    }
}
