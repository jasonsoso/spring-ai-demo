package com.jason.demo.demo2.agentscope.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.rag")
public record AgentscopeRagProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("agentscope-dev-knowledge.txt") String knowledgeFile,
        @DefaultValue("3") int topK,
        @DefaultValue("0.3") double similarityThreshold,
        @DefaultValue("false") boolean reindexOnStartup,
        @DefaultValue("agentscope_dev_knowledge") String tableName,
        @DefaultValue("1024") int embeddingDimensions,
        String embeddingApiKey,
        @DefaultValue("https://open.bigmodel.cn/api/paas/v4") String embeddingBaseUrl,
        @DefaultValue("embedding-2") String embeddingModel) {

    public AgentscopeRagProperties {
        if (knowledgeFile == null || knowledgeFile.isBlank()) {
            knowledgeFile = "agentscope-dev-knowledge.txt";
        }
        if (tableName == null || tableName.isBlank()) {
            tableName = "agentscope_dev_knowledge";
        }
        if (topK <= 0) {
            topK = 3;
        }
        if (embeddingDimensions <= 0) {
            embeddingDimensions = 1024;
        }
        if (embeddingApiKey == null) {
            embeddingApiKey = "";
        }
        if (embeddingBaseUrl == null || embeddingBaseUrl.isBlank()) {
            embeddingBaseUrl = "https://open.bigmodel.cn/api/paas/v4";
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "embedding-2";
        }
    }
}
