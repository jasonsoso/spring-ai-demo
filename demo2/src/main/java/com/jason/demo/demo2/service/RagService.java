package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final List<String> docChunks = new ArrayList<>();
    private volatile List<float[]> docVectors;

    @Value("${rag.knowledge-file:outdoor-travel-safety-guide.txt}")
    private String knowledgeFile;

    @Value("${rag.top-k:2}")
    private int topK;

    private final EmbeddingService.SimilarityAlgorithm similarityAlgorithm =
            EmbeddingService.SimilarityAlgorithm.COSINE;

    public RagService(EmbeddingModel embeddingModel, ChatClient.Builder chatClientBuilder) {
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 索引阶段：加载本地知识库并按分隔符切分为文本片段
     */
    @PostConstruct
    void loadAndSplitDocument() throws IOException {
        Resource resource = new ClassPathResource(knowledgeFile);
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String[] chunks = content.split("----");
        for (String chunk : chunks) {
            String cleanChunk = chunk.strip();
            if (!cleanChunk.isBlank()) {
                docChunks.add(cleanChunk);
            }
        }
        log.info("知识库加载完成，共 {} 个文本片段", docChunks.size());
    }

    /**
     * 索引阶段：文本片段向量化（懒加载，避免启动时 API 未就绪导致失败）
     */
    private List<float[]> getDocVectors() {
        if (docVectors == null) {
            synchronized (this) {
                if (docVectors == null) {
                    log.info("开始向量化知识库片段，共 {} 条...", docChunks.size());
                    docVectors = embeddingModel.embed(docChunks);
                    log.info("知识库向量化完成，向量维度：{}",
                            docVectors.isEmpty() ? 0 : docVectors.get(0).length);
                }
            }
        }
        return docVectors;
    }

    /**
     * RAG 完整流程：检索 TopK 相关片段 → 构建上下文 → 调用大模型生成回答
     */
    public String answer(String question) {
        float[] questionVector = embeddingModel.embed(question);
        List<String> topRelevantChunks = retrieveTopRelevantChunks(questionVector, topK);

        String context = String.join("\n---\n", topRelevantChunks);
        String prompt = String.format(
                "以下是户外旅行安全指南的知识：\n%s\n请基于上述知识，简洁明了地回答问题：%s",
                context, question
        );

        return chatClient.prompt()
                .system("你是户外旅行安全助手，仅基于提供的上下文回答问题，不添加额外信息。")
                .user(prompt)
                .call()
                .content();
    }

    /**
     * 检索阶段：按相似度排序，返回 TopK 最相关文本片段
     */
    public List<String> retrieveTopRelevantChunks(float[] questionVector, int topK) {
        List<float[]> vectors = getDocVectors();
        List<ChunkSimilarity> similarityList = new ArrayList<>();

        for (int i = 0; i < vectors.size(); i++) {
            double sim = calculateSimilarity(questionVector, vectors.get(i));
            similarityList.add(new ChunkSimilarity(i, sim));
        }

        return similarityList.stream()
                .sorted(Comparator.comparingDouble((ChunkSimilarity c) -> c.similarity).reversed())
                .limit(topK)
                .map(item -> docChunks.get(item.index))
                .toList();
    }

    private double calculateSimilarity(float[] a, float[] b) {
        return switch (similarityAlgorithm) {
            case COSINE -> SimilarityCalculator.cosineSimilarity(a, b);
            case EUCLIDEAN -> SimilarityCalculator.euclideanSimilarity(a, b);
            case MANHATTAN -> SimilarityCalculator.manhattanSimilarity(a, b);
        };
    }

    private static class ChunkSimilarity {
        final int index;
        final double similarity;

        ChunkSimilarity(int index, double similarity) {
            this.index = index;
            this.similarity = similarity;
        }
    }
}
