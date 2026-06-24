package com.jason.demo.demo2.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * 电商客服知识库初始化配置：解析文档 → 文本切分 → 向量入库（Milvus）
 */
@Component
public class KnowledgeBaseConfig {

    private final VectorStore vectorStore;

    @Value("${ecommerce.knowledge-file:ecommerce-knowledge-base.txt}")
    private String knowledgeFile;

    @Value("${ecommerce.reindex-on-startup:false}")
    private boolean reindexOnStartup;

    public KnowledgeBaseConfig(@Lazy VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 项目启动时初始化知识库：解析文档 → 切分文本 → 向量入库
     */
    @PostConstruct
    public void initKnowledgeBase() {
        if (!reindexOnStartup) {
            System.out.println("跳过电商知识库初始化（ecommerce.reindex-on-startup=false），已有数据可直接使用");
            return;
        }

        try {
            System.out.println("开始初始化电商客服知识库...");
            List<String> docFiles = List.of(knowledgeFile);

            List<Document> allSplitDocs = new ArrayList<>();
            for (String fileName : docFiles) {
                // 使用 TikaDocumentReader 解析文档（支持 txt、docx、pdf 等多种格式）
                Resource resource = new ClassPathResource(fileName);
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<Document> rawDocs = reader.read();

                // 优化文本切分策略（适配电商规则条款化特点）
                TokenTextSplitter splitter = TokenTextSplitter.builder()
                        .withChunkSize(600)
                        .withMinChunkSizeChars(200)
                        .withKeepSeparator(true)
                        .build();

                List<Document> splitDocs = splitter.apply(rawDocs);
                allSplitDocs.addAll(splitDocs);
                System.out.println("已解析文档：" + fileName + "，生成 " + splitDocs.size() + " 个文本片段");
            }

            // 批量向量入库（Spring AI 自动调用 EmbeddingModel 完成向量化）
            vectorStore.add(allSplitDocs);
            System.out.println("知识库初始化完成，共导入 " + allSplitDocs.size() + " 个文本片段");
        } catch (Exception e) {
            System.err.println("知识库初始化失败：" + e.getMessage());
            e.printStackTrace();
        }
    }
}
