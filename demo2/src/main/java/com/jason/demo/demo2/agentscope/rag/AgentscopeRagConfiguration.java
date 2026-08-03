package com.jason.demo.demo2.agentscope.rag;

import com.jason.demo.demo2.agentscope.config.AgentScopeDataSourceProperties;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.PgVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 装配 {@link SimpleKnowledge} + PgVector；失败时降级为 unavailable Holder。
 */
@Configuration
@EnableConfigurationProperties(AgentscopeRagProperties.class)
@SuppressWarnings("deprecation")
// Temporary: agentscope-extensions-rag-simple + deprecated Knowledge until AgentScope v2 RAG APIs.
// See docs/superpowers/specs/2026-08-03-agentscope-app-layer-rag-design.md §8
public class AgentscopeRagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeRagConfiguration.class);

    @Bean
    AgentscopeRagKnowledgeHolder agentscopeRagKnowledgeHolder(
            AgentscopeRagProperties rag,
            AgentScopeDataSourceProperties ds) {
        if (!rag.enabled() || rag.embeddingApiKey() == null || rag.embeddingApiKey().isBlank()) {
            log.warn("AgentScope RAG disabled or missing ZHIPU embedding key; degrade to unavailable");
            return AgentscopeRagKnowledgeHolder.unavailable(rag);
        }
        try {
            ensureVectorExtension(ds);
            PgVectorStore store = PgVectorStore.builder()
                    .jdbcUrl(ds.url())
                    .username(ds.username())
                    .password(ds.password())
                    .tableName(rag.tableName())
                    .dimensions(rag.embeddingDimensions())
                    .connectionTimeoutMs(ds.connectionTimeoutMs())
                    .build();
            OpenAITextEmbedding embedding = OpenAITextEmbedding.builder()
                    .apiKey(rag.embeddingApiKey())
                    .baseUrl(rag.embeddingBaseUrl())
                    .modelName(rag.embeddingModel())
                    .dimensions(rag.embeddingDimensions())
                    .build();
            SimpleKnowledge knowledge = SimpleKnowledge.builder()
                    .embeddingModel(embedding)
                    .embeddingStore(store)
                    .build();
            ingestIfNeeded(knowledge, store, rag);
            RetrieveConfig retrieveConfig = RetrieveConfig.builder()
                    .limit(rag.topK())
                    .scoreThreshold(rag.similarityThreshold())
                    .build();
            log.info("AgentScope RAG ready: table={}, topK={}", rag.tableName(), rag.topK());
            return AgentscopeRagKnowledgeHolder.available(knowledge, retrieveConfig);
        } catch (Exception ex) {
            log.warn("AgentScope RAG init failed, degrade: {}", ex.toString());
            return AgentscopeRagKnowledgeHolder.unavailable(rag);
        }
    }

    static void ensureVectorExtension(AgentScopeDataSourceProperties ds) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", ds.username());
        props.setProperty("password", ds.password());
        try (Connection conn = DriverManager.getConnection(ds.url(), props);
             Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
    }

    static void ingestIfNeeded(
            SimpleKnowledge knowledge,
            PgVectorStore store,
            AgentscopeRagProperties rag) throws Exception {
        String table = rag.tableName();
        if (!isSafeSqlIdent(table)) {
            throw new IllegalArgumentException("Unsafe rag table name: " + table);
        }
        if (rag.reindexOnStartup()) {
            try (Connection conn = store.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("TRUNCATE TABLE " + table);
            }
            addDocuments(knowledge, rag);
            return;
        }
        long count = countRows(store, table);
        if (count == 0L) {
            addDocuments(knowledge, rag);
        } else {
            log.info("AgentScope RAG skip ingest: table {} already has {} rows", table, count);
        }
    }

    private static void addDocuments(SimpleKnowledge knowledge, AgentscopeRagProperties rag) throws Exception {
        ClassPathResource resource = new ClassPathResource(rag.knowledgeFile());
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String> chunks = AgentscopeRagKnowledgeHolder.splitChunks(content);
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(chunks.get(i)).build())
                    .docId("agentscope-dev")
                    .chunkId("c-" + i)
                    .build();
            docs.add(new Document(metadata));
        }
        knowledge.addDocuments(docs).block();
        log.info("AgentScope RAG ingested {} chunks from {}", docs.size(), rag.knowledgeFile());
    }

    private static long countRows(PgVectorStore store, String table) throws Exception {
        try (Connection conn = store.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (Exception ex) {
            log.warn("AgentScope RAG count failed (will try ingest): {}", ex.toString());
            return 0L;
        }
    }

    /** 仅允许配置侧表名：字母数字下划线。 */
    static boolean isSafeSqlIdent(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }
}
