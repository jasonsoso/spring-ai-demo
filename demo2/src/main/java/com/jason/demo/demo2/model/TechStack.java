package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 技术栈推荐结构化输出
 */
@Schema(description = "技术栈推荐项")
public record TechStack(

        @Schema(description = "技术栈名称", example = "Spring Boot + Vue 3")
        String name,

        @Schema(description = "适用场景", example = "企业级后台管理系统")
        String useCase,

        @Schema(description = "成熟度", example = "成熟")
        String maturityLevel
) {
}
