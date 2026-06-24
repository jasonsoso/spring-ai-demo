package com.jason.demo.demo.controller;

import com.jason.demo.demo.service.RagOptimizedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "RAG 知识库（Milvus 优化版）")
@RestController
@RequestMapping("/rag/optimized")
public class RagOptimizedController {

    @Autowired
    private RagOptimizedService ragOptimizedService;

    /**
     * 优化版 RAG 本地知识库检索接口
     * GET /rag/optimized/ask?question=露营选址有什么安全要求？
     */
    @Operation(summary = "Milvus 知识库问答", description = "基于 Milvus 向量数据库的优化版 RAG 检索问答")
    @GetMapping("/ask")
    public Map<String, String> ask(@Parameter(description = "用户问题", example = "露营选址有什么安全要求？") @RequestParam("question") String question) {
        String answer = ragOptimizedService.answer(question);
        return Map.of(
                "question", question,
                "answer", answer
        );
    }
}
