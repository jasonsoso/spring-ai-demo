package com.jason.demo.demo.controller;

import com.jason.demo.demo.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "RAG 知识库")
@RestController
@RequestMapping("/rag")
public class RagController {

    @Autowired
    private RagService ragService;

    /**
     * RAG 本地知识库检索接口
     * GET /rag/ask?question=露营选址有什么安全要求？
     */
    @Operation(summary = "知识库问答", description = "基于内存向量检索的 RAG 问答")
    @GetMapping("/ask")
    public Map<String, String> ask(@Parameter(description = "用户问题", example = "露营选址有什么安全要求？") @RequestParam("question") String question) {
        String answer = ragService.answer(question);
        return Map.of(
                "question", question,
                "answer", answer
        );
    }
}
