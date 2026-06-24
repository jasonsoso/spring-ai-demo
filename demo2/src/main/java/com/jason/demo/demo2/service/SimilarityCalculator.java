package com.jason.demo.demo2.service;

/**
 * 基于 Embedding 向量的文本相似度计算工具类
 * 支持三种核心相似度算法：余弦相似度、欧氏距离、曼哈顿距离
 */
public class SimilarityCalculator {

    // ====================== 1. 余弦相似度（默认推荐） ======================
    public static double cosineSimilarity(float[] a, float[] b) {
        checkVectorLength(a, b);
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += Math.pow(a[i], 2);
            normB += Math.pow(b[i], 2);
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ====================== 2. 欧氏距离（归一化后） ======================
    public static double euclideanSimilarity(float[] a, float[] b) {
        checkVectorLength(a, b);
        double sum = 0.0;

        for (int i = 0; i < a.length; i++) {
            sum += Math.pow(a[i] - b[i], 2);
        }

        double distance = Math.sqrt(sum);
        // 归一化：1/(1+距离)，将距离转换为相似度（值越大相似度越高）
        return 1.0 / (1.0 + distance);
    }

    // ====================== 3. 曼哈顿距离（归一化后） ======================
    public static double manhattanSimilarity(float[] a, float[] b) {
        checkVectorLength(a, b);
        double sum = 0.0;

        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }

        // 归一化：1/(1+距离)
        return 1.0 / (1.0 + sum);
    }

    private static void checkVectorLength(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("向量长度不一致，无法计算相似度");
        }
    }
}
