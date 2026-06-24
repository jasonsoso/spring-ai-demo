package com.jason.demo.demo2.service;

import com.jason.demo.demo2.tools.AttractionTool;
import com.jason.demo.demo2.tools.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 带工具调用能力的智能行程规划 Agent
 * 核心能力：自动判断并调用天气、景点工具，生成个性化行程
 * Chat 走 DeepSeek，Embedding 仍走智谱 embedding-2
 */
@Slf4j
@Service
public class ToolTripAgentService {

    private static final String SYSTEM_PROMPT = """
            你是一个具备工具调用能力的智能行程规划 Agent，核心规则如下：

            1. 当用户询问天气时，必须调用 getWeather 工具获取实时天气数据；
            2. 当用户需要景点推荐时，必须调用 recommendAttractions 工具获取景点信息；
            3. 结合天气数据和景点信息，生成完整的行程规划建议；
            4. 行程安排要考虑天气因素（如雨天推荐室内景点，晴天推荐户外景点）；
            5. 输出结构清晰，包含天气概况、推荐景点、行程安排、实用提示；
            6. 所有实时信息必须通过工具获取，严禁编造天气或景点数据。

            回复风格：简洁专业，突出实用信息，适合移动端阅读。
            """;

    private final ChatClient chatClient;

    public ToolTripAgentService(ChatClient.Builder chatClientBuilder,
                                WeatherTool weatherTool,
                                AttractionTool attractionTool) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(weatherTool, attractionTool)
                .build();
    }

    /**
     * 带工具调用的行程规划
     *
     * @param demand 用户出行需求（可包含天气查询、景点推荐、行程规划）
     * @return 结合实时数据的个性化行程规划
     */
    public String planTripWithTools(String demand) {
        try {
            return chatClient.prompt()
                    .user(demand)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("带工具调用的 Agent 执行失败", e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }
}
