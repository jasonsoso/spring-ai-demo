package com.jason.demo.demo2.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 行程规划子 Agent（Itinerary Agent）
 * 职责：根据目的地、天数、偏好，输出逐日景点游览顺序与交通安排方案
 * Chat 模型：DeepSeek
 */
@Slf4j
@Component
public class ItineraryAgent {

    private static final String ITINERARY_SYSTEM = """
            你是专业的旅游行程规划专家（Itinerary Agent）。
            根据用户提供的目的地、旅行天数、人数及景点偏好，输出详细的逐日行程安排：
            - 按天（第 N 天）展开，每天分为上午 / 中午 / 下午 / 晚上
            - 每个时段注明：景点名称、建议游览时长、简短亮点介绍
            - 合理安排景点动线，避免来回折返；考虑开闭馆时间与人流高峰
            - 每日行程末尾附上当日餐饮衔接建议（简要即可，预算/天气由其他 Agent 负责）
            - 输出格式清晰，可直接作为旅游手册使用
            """;

    private final ChatClient chatClient;

    public ItineraryAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone().defaultSystem(ITINERARY_SYSTEM).build();
    }

    /**
     * 根据需求摘要规划逐日行程
     *
     * @param agentInput 包含用户原始需求与 Supervisor 任务摘要的综合输入
     * @return 逐日行程规划文本
     */
    public String plan(String agentInput) {
        log.info("[ItineraryAgent] 开始规划行程...");
        try {
            String result = chatClient.prompt()
                    .user("请根据以下旅游需求，输出详细逐日行程安排：\n" + agentInput)
                    .call()
                    .content();
            log.info("[ItineraryAgent] 行程规划完成");
            return result;
        } catch (Exception e) {
            log.error("[ItineraryAgent] 行程规划失败", e);
            return "⚠️ 行程规划 Agent 暂时不可用：" + e.getMessage();
        }
    }
}
