package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.service.MysqlMemoryTripAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MySQL 持久化记忆 Agent 控制器。
 */
@Tag(name = "MySQL 持久化记忆 Agent", description = "基于 JDBC ChatMemoryRepository 的长期记忆行程规划接口")
@RestController
@RequestMapping("/agent/mysql/trip")
@RequiredArgsConstructor
public class MysqlAgentController {

    private final MysqlMemoryTripAgentService mysqlMemoryTripAgentService;

    /**
     * MySQL 记忆行程规划接口。
     */
    @Operation(summary = "MySQL 持久化记忆行程规划",
            description = "通过 userId 关联 MySQL 持久化记忆；memoryType 参数保留兼容，Spring AI 2.0 统一使用 MessageChatMemoryAdvisor")
    @GetMapping("/plan")
    public Map<String, Object> planTrip(
            @Parameter(description = "用户唯一标识", example = "1001")
            @RequestParam("userId") String userId,
            @Parameter(description = "出行需求", example = "周末两天厦门游，2人，偏好海滨景点，不吃海鲜，交通以地铁和网约车为主")
            @RequestParam("demand") String demand,
            @Parameter(description = "记忆类型：message / prompt", example = "message")
            @RequestParam(value = "memoryType", defaultValue = "message") String memoryType) {
        String normalizedMemoryType = "prompt".equalsIgnoreCase(memoryType) ? "prompt" : "message";
        String tripPlan = mysqlMemoryTripAgentService.planTripWithMysqlMemory(userId, demand, normalizedMemoryType);

        return Map.of(
                "code", 200,
                "msg", "success",
                "data", Map.of(
                        "userId", userId,
                        "memoryType", normalizedMemoryType,
                        "userDemand", demand,
                        "tripPlan", tripPlan,
                        "storageType", "MySQL 持久化"
                )
        );
    }

    /**
     * 清除用户 MySQL 记忆接口。
     */
    @Operation(summary = "清除用户 MySQL 记忆", description = "清除指定 userId 的 MySQL 持久化聊天记忆")
    @GetMapping("/clear-memory")
    public Map<String, Object> clearMemory(
            @Parameter(description = "用户唯一标识", example = "1001")
            @RequestParam("userId") String userId) {
        mysqlMemoryTripAgentService.clearUserMemory(userId);
        return Map.of(
                "code", 200,
                "msg", "用户 MySQL 记忆清除成功",
                "data", Map.of("userId", userId)
        );
    }

    /**
     * 查询所有对话 ID。
     */
    @Operation(summary = "查询 MySQL 记忆会话 ID", description = "列出 JDBC 记忆表中已有的 conversationId")
    @GetMapping("/list-conversations")
    public Map<String, Object> listConversations() {
        List<String> conversationIds = mysqlMemoryTripAgentService.listAllConversationIds();
        return Map.of(
                "code", 200,
                "msg", "success",
                "data", Map.of(
                        "conversationCount", conversationIds.size(),
                        "conversationIds", conversationIds
                )
        );
    }
}
