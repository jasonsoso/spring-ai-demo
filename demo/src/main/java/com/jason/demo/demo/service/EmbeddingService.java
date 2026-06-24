package com.jason.demo.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    // 本地知识库文本（旅行/户外场景）
    private final List<String> docs = List.of(
            "海边露营需准备防水帐篷、防潮垫、速干衣和便携炊具。",
            "山地徒步要携带登山杖、防滑鞋、双肩包和应急医疗包。",
            "城市漫游推荐骑行共享单车，打卡老街区和小众咖啡馆。"
    );

    // 知识库文本对应的向量（懒加载，首次查询时初始化）
    private volatile List<float[]> docVectors;

    public enum SimilarityAlgorithm {
        COSINE,
        EUCLIDEAN,
        MANHATTAN
    }

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 获取知识库向量（懒加载，避免启动时因API Key未配置导致失败）
     */
    private List<float[]> getDocVectors() {
        if (docVectors == null) {
            synchronized (this) {
                if (docVectors == null) {
                    log.info("初始化知识库向量，共 {} 条文档...", docs.size());
                    docVectors = embeddingModel.embed(docs);
                    log.info("知识库向量初始化完成，向量维度：{}", docVectors.isEmpty() ? 0 : docVectors.get(0).length);
                }
            }
        }
        return docVectors;
    }

    /**
     * 获取知识库文档列表
     */
    public List<String> getDocs() {
        return docs;
    }

    /**
     * 查找与查询文本最相似的知识库文本（指定算法）
     */
    public String queryBestMatch(String query, SimilarityAlgorithm algorithm) {
        float[] queryVec = embeddingModel.embed(query);
        List<float[]> vectors = getDocVectors();

        int bestIdx = 0;
        double bestSim = -1;

        for (int i = 0; i < vectors.size(); i++) {
            double sim = calculateSimilarity(queryVec, vectors.get(i), algorithm);
            if (sim > bestSim) {
                bestSim = sim;
                bestIdx = i;
            }
        }
        return docs.get(bestIdx);
    }

    /**
     * 查找最相似文本，默认使用余弦相似度
     */
    public String queryBestMatch(String query) {
        return queryBestMatch(query, SimilarityAlgorithm.COSINE);
    }

    private double calculateSimilarity(float[] a, float[] b, SimilarityAlgorithm algorithm) {
        return switch (algorithm) {
            case COSINE -> SimilarityCalculator.cosineSimilarity(a, b);
            case EUCLIDEAN -> SimilarityCalculator.euclideanSimilarity(a, b);
            case MANHATTAN -> SimilarityCalculator.manhattanSimilarity(a, b);
        };
    }
}
