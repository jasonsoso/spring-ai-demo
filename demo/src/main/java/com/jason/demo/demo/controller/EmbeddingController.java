package com.jason.demo.demo.controller;

import com.jason.demo.demo.service.EmbeddingService;
import com.jason.demo.demo.service.EmbeddingService.SimilarityAlgorithm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Embedding 向量化")
@RestController
@RequestMapping("/ai")
public class EmbeddingController {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 文本向量化接口
     * GET /ai/embedding?message=推荐一款入门级露营装备
     */
    @Operation(summary = "文本向量化", description = "将文本转换为 Embedding 向量（智谱 AI）")
    @GetMapping("/embedding")
    public Map<String, Object> embedding(
            @Parameter(description = "待向量化的文本") @RequestParam(value = "message", defaultValue = "推荐一款入门级露营装备") String message) {
        float[] vector = embeddingModel.embed(message);
        return Map.of(
                "message", message,
                "vectorDimension", vector.length,
                "vector", vector
        );
    }

    /**
     * 相似文本查找接口（支持指定相似度算法）
     * GET /ai/similarity?query=露营准备&algorithm=COSINE
     */
    @Operation(summary = "相似文本查找", description = "根据查询文本在知识库中查找最相似内容，支持多种相似度算法")
    @GetMapping("/similarity")
    public Map<String, Object> findSimilarText(
            @Parameter(description = "查询文本") @RequestParam("query") String query,
            @Parameter(description = "相似度算法：COSINE / EUCLIDEAN / MANHATTAN") @RequestParam(value = "algorithm", defaultValue = "COSINE") String algorithm) {

        SimilarityAlgorithm simAlgo;
        try {
            simAlgo = SimilarityAlgorithm.valueOf(algorithm.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Map.of(
                    "error", "算法参数无效，支持：COSINE / EUCLIDEAN / MANHATTAN",
                    "code", 400
            );
        }

        String similarText = embeddingService.queryBestMatch(query, simAlgo);
        return Map.of(
                "query", query,
                "algorithm", simAlgo.name(),
                "answer", similarText
        );
    }
}
