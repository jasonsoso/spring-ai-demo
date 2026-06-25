package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 产品分析结构化输出
 */
@Schema(description = "产品分析结果")
public record ProductAnalysis(

        @Schema(description = "产品名称", example = "iPhone 16 Pro")
        String productName,

        @Schema(description = "优点列表")
        List<String> pros,

        @Schema(description = "缺点列表")
        List<String> cons,

        @Schema(description = "推荐指数，1-10 分", example = "8")
        int score,

        @Schema(description = "购买建议")
        String recommendation
) {
}
