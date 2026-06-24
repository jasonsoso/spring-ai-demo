package com.jason.demo.demo.controller;

import com.jason.demo.demo.service.MemoryTripAgentService;
import com.jason.demo.demo.service.TripPlanningAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI Agent 控制器：提供智能行程规划接口
 */
@Tag(name = "AI Agent", description = "智能行程规划 Agent（DeepSeek 驱动，含带记忆进阶版）")
@RestController
@RequestMapping("/agent/trip")
public class AgentController {

    @Autowired
    private TripPlanningAgentService tripPlanningAgentService;

    @Autowired
    private MemoryTripAgentService memoryTripAgentService;

    /**
     * 行程规划接口：接收出行需求，返回完整行程方案
     */
    @Operation(summary = "智能行程规划",
            description = "输入出行需求，Agent 自动拆解并生成完整行程方案（DeepSeek 模型）")
    @GetMapping("/plan")
    public Map<String, String> planTrip(
            @Parameter(description = "出行需求", example = "周末两天北京短途游，2人，偏好人文景点，不吃辣，交通以地铁为主")
            @RequestParam("demand") String demand) {
        String tripPlan = tripPlanningAgentService.planTrip(demand);
        return Map.of(
                "userDemand", demand,
                "tripPlan", tripPlan,
                "agentType", "智能行程规划 Agent"
        );
    }

    /**
     * 带记忆的行程规划接口：通过 userId 隔离不同用户记忆
     */
    @Operation(summary = "带记忆行程规划",
            description = "通过 userId 关联聊天记忆，二次查询可自动复用用户偏好（DeepSeek + MessageChatMemoryAdvisor）")
    @GetMapping("/plan-with-memory")
    public Map<String, String> planTripWithMemory(
            @Parameter(description = "用户唯一标识", example = "1001")
            @RequestParam("userId") String userId,
            @Parameter(description = "出行需求", example = "周末两天成都短途游，2人，偏好自然景点，不吃辣，交通以地铁和打车为主")
            @RequestParam("demand") String demand) {
        String tripPlan = memoryTripAgentService.planTripWithMemory(userId, demand);
        return Map.of(
                "userId", userId,
                "userDemand", demand,
                "tripPlan", tripPlan,
                "agentType", "带记忆的智能行程规划 Agent"
        );
    }

    /**
     * 清除指定用户的聊天记忆
     */
    @Operation(summary = "清除用户记忆", description = "清除指定 userId 的聊天记忆")
    @DeleteMapping("/clear-memory")
    public Map<String, String> clearMemory(
            @Parameter(description = "用户唯一标识", example = "1001")
            @RequestParam("userId") String userId) {
        memoryTripAgentService.clearUserMemory(userId);
        return Map.of(
                "userId", userId,
                "message", "用户记忆已清除"
        );
    }
}
