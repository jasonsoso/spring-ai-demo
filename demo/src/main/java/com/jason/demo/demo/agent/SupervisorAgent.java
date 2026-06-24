package com.jason.demo.demo.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 总调度 Agent（Supervisor Agent）
 * 职责：
 * 1. decompose()  —— 分析用户旅游需求，提炼关键要素，生成任务摘要供子 Agent 参考
 * 2. synthesize() —— 整合天气/行程/预算三个子 Agent 的规划结果，输出完整行程方案
 *
 * Chat 模型：DeepSeek（spring.ai.model.chat=deepseek）
 * Embedding：智谱 embedding-2（本 Agent 无需调用）
 */
@Slf4j
@Component
public class SupervisorAgent {

    private static final String DECOMPOSE_SYSTEM = """
            你是多 Agent 旅游规划系统的总调度（Supervisor Agent）。
            任务：分析用户旅游需求，结构化提炼关键要素，作为下游各专项 Agent 的输入参考。
            输出格式（严格遵守，逐行列出）：
            - 目的地：
            - 旅行天数：
            - 出行人数：
            - 总预算：
            - 景点偏好（人文/自然/娱乐等）：
            - 饮食禁忌：
            - 交通偏好：
            - 特殊需求：
            输出简洁准确，不做多余解释。
            """;

    private static final String SYNTHESIZE_SYSTEM = """
            你是多 Agent 旅游规划系统的综合输出者（Supervisor Synthesize）。
            任务：整合天气分析、行程规划、预算分配三个子 Agent 的规划结果，
            生成一份完整、连贯、可直接使用的旅游行程方案。
            输出结构（按序输出）：
            1. 【行程总览】2-3 句话概括整体安排与亮点
            2. 【天气与时机】基于天气分析给出出行最佳时段建议
            3. 【逐日行程】按天（上午/下午/晚上）展开，整合景点+餐饮+交通
            4. 【住宿建议】结合行程位置给出每晚住宿推荐
            5. 【预算总览】各项费用分配（交通/住宿/餐饮/门票/其他）
            6. 【实用贴士】天气穿搭、预约注意、必备物品
            语言简洁，格式清晰，便于直接执行。
            """;

    private final ChatClient decomposeClient;
    private final ChatClient synthesizeClient;

    public SupervisorAgent(ChatClient.Builder chatClientBuilder) {
        this.decomposeClient  = chatClientBuilder.clone().defaultSystem(DECOMPOSE_SYSTEM).build();
        this.synthesizeClient = chatClientBuilder.clone().defaultSystem(SYNTHESIZE_SYSTEM).build();
    }

    /**
     * Step 1：需求分解
     * 将用户自然语言需求提炼为结构化任务摘要，供三个子 Agent 参考
     */
    public String decompose(String demand) {
        log.info("[SupervisorAgent] 开始分解需求: {}", demand);
        try {
            String result = decomposeClient.prompt()
                    .user("请分析并结构化以下旅游需求：\n" + demand)
                    .call()
                    .content();
            log.info("[SupervisorAgent] 需求分解完成");
            return result;
        } catch (Exception e) {
            log.error("[SupervisorAgent] 需求分解失败", e);
            return "旅游需求：" + demand;
        }
    }

    /**
     * Step 3：结果综合
     * 整合三个子 Agent 的输出，生成最终完整行程方案
     *
     * @param demand          用户原始需求
     * @param taskBrief       Supervisor 分解的任务摘要
     * @param weatherAnalysis WeatherAgent 的天气分析结果
     * @param itineraryPlan   ItineraryAgent 的行程规划结果
     * @param budgetPlan      BudgetAgent 的预算分配结果
     */
    public String synthesize(String demand, String taskBrief,
                             String weatherAnalysis, String itineraryPlan, String budgetPlan) {
        log.info("[SupervisorAgent] 开始综合各子 Agent 结果...");
        String synthesisPrompt = """
                请整合以下三个子 Agent 的规划结果，生成完整行程方案：
                
                【用户原始需求】
                %s
                
                【需求摘要（Supervisor 提炼）】
                %s
                
                ━━━ 天气分析 Agent 输出 ━━━
                %s
                
                ━━━ 行程规划 Agent 输出 ━━━
                %s
                
                ━━━ 预算分配 Agent 输出 ━━━
                %s
                """.formatted(demand, taskBrief, weatherAnalysis, itineraryPlan, budgetPlan);
        try {
            String result = synthesizeClient.prompt()
                    .user(synthesisPrompt)
                    .call()
                    .content();
            log.info("[SupervisorAgent] 综合完成");
            return result;
        } catch (Exception e) {
            log.error("[SupervisorAgent] 综合失败，降级拼接", e);
            return buildFallback(weatherAnalysis, itineraryPlan, budgetPlan);
        }
    }

    private String buildFallback(String weather, String itinerary, String budget) {
        return "【天气分析】\n" + weather
                + "\n\n【行程规划】\n" + itinerary
                + "\n\n【预算分配】\n" + budget;
    }
}
