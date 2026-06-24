package com.jason.demo.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 生产级 RAG：Milvus 向量持久化 + TokenTextSplitter 语义切分 + 相似度阈值 + 相邻片段扩展
 */
@Slf4j
@Service
public class RagOptimizedService {

    private static final String MILVUS_ERROR_MSG = "Milvus 连接失败，请确认 Milvus 已运行（localhost:19530）";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final List<String> docChunks = new ArrayList<>();

    @Value("${rag.knowledge-file:outdoor-travel-safety-guide.txt}")
    private String knowledgeFile;

    @Value("${rag.optimized.top-k:5}")
    private int topK;

    @Value("${rag.optimized.similarity-threshold:0.05}")
    private double similarityThreshold;

    @Value("${rag.optimized.reindex-on-startup:true}")
    private boolean reindexOnStartup;

    public RagOptimizedService(ChatClient.Builder chatClientBuilder, @Lazy VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    /**
     * 启动时加载知识库、Token 切分，并按配置尝试写入 Milvus（失败不影响启动）
     */
    @PostConstruct
    public void initKnowledgeBase() throws IOException {
        log.info("开始初始化优化版知识库，加载文件并写入 Milvus...");

        Resource resource = new ClassPathResource(knowledgeFile);
        TextReader textReader = new TextReader(resource);
        List<Document> rawDocs = textReader.read();

        TokenTextSplitter textSplitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(400)
                .withKeepSeparator(true)
                .build();
        List<Document> splitDocs = textSplitter.apply(rawDocs);

        docChunks.clear();
        List<Document> docsToIndex = new ArrayList<>();
        for (Document doc : splitDocs) {
            String text = doc.getText().strip();
            if (text.isBlank()) {
                continue;
            }
            docChunks.add(text);

            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            metadata.put("chunk_index", docChunks.size() - 1);
            metadata.put("source", knowledgeFile);
            docsToIndex.add(new Document(text, metadata));
        }

        if (reindexOnStartup && !docsToIndex.isEmpty()) {
            try {
                vectorStore.add(docsToIndex);
                log.info("知识库向量已写入 Milvus，共 {} 个文本片段", docChunks.size());
            } catch (Exception e) {
                log.error("启动时写入 Milvus 失败，应用继续启动：{}", e.getMessage());
            }
        } else if (!reindexOnStartup) {
            log.info("跳过向量入库（reindex-on-startup=false），共 {} 个文本片段已加载到内存", docChunks.size());
        }
    }

    /**
     * 优化版 RAG：Milvus 检索 + 阈值过滤 + 相邻片段扩展 → 大模型生成
     */
    public String answer(String question) {
        List<String> relevantChunks;
        try {
            relevantChunks = retrieveRelevantChunks(question, topK, similarityThreshold);
        } catch (Exception e) {
            log.error("Milvus 检索失败", e);
            return MILVUS_ERROR_MSG;
        }

        if (relevantChunks.isEmpty()) {
            return "抱歉，未查询到相关安全指南信息";
        }

        String context = relevantChunks.stream()
                .distinct()
                .collect(Collectors.joining("\n---\n"));

        String prompt = String.format(
                "以下是户外旅行安全指南的知识库内容：\n%s\n请严格基于上述内容，简洁、准确地回答用户问题，不要添加额外信息。问题：%s",
                context, question
        );

        return chatClient.prompt()
                .system("你是专业的户外旅行安全助手，仅基于提供的上下文回答问题，若上下文无相关信息，回复'抱歉，未查询到相关安全指南信息'。")
                .user(prompt)
                .call()
                .content();
    }

    /**
     * Milvus 向量检索 + 相似度阈值 + 相邻片段扩展
     */
    private List<String> retrieveRelevantChunks(String question, int topK, double threshold) {
        MilvusSearchRequest searchRequest = MilvusSearchRequest.milvusBuilder()
                .query(question)
                .topK(topK)
                .similarityThreshold(threshold)
                .searchParamsJson("{\"nprobe\":128}")
                .build();

        List<Document> searchResults = vectorStore.similaritySearch(searchRequest);
        if (searchResults.isEmpty()) {
            return List.of();
        }

        Set<Integer> targetIndexes = new TreeSet<>();
        for (Document doc : searchResults) {
            Object indexObj = doc.getMetadata().get("chunk_index");
            if (indexObj == null) {
                continue;
            }
            int index = ((Number) indexObj).intValue();
            targetIndexes.add(index);
            if (index - 1 >= 0) {
                targetIndexes.add(index - 1);
            }
            if (index + 1 < docChunks.size()) {
                targetIndexes.add(index + 1);
            }
        }

        return targetIndexes.stream()
                .map(docChunks::get)
                .collect(Collectors.toList());
    }
}
