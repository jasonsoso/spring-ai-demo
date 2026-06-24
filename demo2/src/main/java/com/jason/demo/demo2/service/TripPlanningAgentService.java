package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 智能行程规划 Agent 服务
 * 基于 DeepSeek 大模型，通过系统提示词实现任务拆解与行程规划
 */
@Slf4j
@Service
public class TripPlanningAgentService {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的智能行程规划 Agent，核心职责是根据用户出行需求，生成完整、可执行的行程方案，规则如下：
            1. 先拆解需求核心要素：出行时间、目的地、人数、偏好（景点类型、饮食、交通方式）、禁忌；
            2. 行程按天拆分，每天按时间段（上午/下午/晚上）规划，包含景点、交通、餐饮、停留时长；
            3. 景点选择贴合用户偏好，餐饮适配饮食禁忌，交通路线合理（避免绕路）；
            4. 补充实用提示（如景点开放时间、预约要求、穿搭建议）；
            5. 语言简洁明了，结构清晰，便于用户直接参考执行。
            """;

    private final ChatClient chatClient;

    public TripPlanningAgentService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Agent 核心能力：接收出行需求，生成完整行程规划
     *
     * @param tripDemand 用户出行需求（包含时间、地点、偏好等）
     * @return 完整行程规划（按天/按时段拆分）
     */
    public String planTrip(String tripDemand) {
        try {
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("请根据以下出行需求生成完整行程规划：" + tripDemand)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("调用 DeepSeek 模型失败", e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }
}
