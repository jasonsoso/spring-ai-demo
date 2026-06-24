package com.jason.demo.demo2.agent;

import com.jason.demo.demo2.tool.TimeMethodTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 天气分析子 Agent（Weather Agent）
 * 职责：结合目的地气候与出行时间，提供天气规律、出行时机及穿搭建议
 * 特点：携带 TimeMethodTool，LLM 可通过 Function Calling 主动查询目的地当前时间与季节，
 *       实现基于真实时区的天气分析，而非依赖静态知识
 * Chat 模型：DeepSeek
 */
@Slf4j
@Component
public class WeatherAgent {

    private static final String WEATHER_SYSTEM = """
            你是专业的旅游天气分析专家（Weather Agent）。
            你拥有一个时间查询工具（getCityCurrentTime），可获取目的地当前时间、季节及气候提示。
            分析任务：
            1. 调用时间工具确认目的地当前季节和月份
            2. 结合该季节的气候规律，分析旅行期间的天气特点（温度区间、降雨概率、风力等）
            3. 给出最佳游览时段（全天哪些时段户外活动最舒适）
            4. 推荐穿搭：分早中晚给出穿衣建议
            5. 特殊天气提示：如有极端天气风险（暴雨、高温等）需重点提醒
            输出：条目清晰，简洁实用，供行程规划参考。
            """;

    private final ChatClient chatClient;

    /**
     * WeatherAgent 构造时将 TimeMethodTool 注册为可调用工具
     * LLM 在分析天气时可主动调用该工具获取实时时间信息
     */
    public WeatherAgent(ChatClient.Builder chatClientBuilder, TimeMethodTool timeMethodTool) {
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem(WEATHER_SYSTEM)
                .defaultTools(timeMethodTool)
                .build();
    }

    /**
     * 分析目的地天气与出行时机
     *
     * @param agentInput 包含用户原始需求与 Supervisor 任务摘要的综合输入
     * @return 天气分析与穿搭建议文本
     */
    public String analyze(String agentInput) {
        log.info("[WeatherAgent] 开始天气分析（含时间工具调用）...");
        try {
            String result = chatClient.prompt()
                    .user("请分析目的地旅行期间的天气情况，并给出出行时机和穿搭建议：\n" + agentInput)
                    .call()
                    .content();
            log.info("[WeatherAgent] 天气分析完成");
            return result;
        } catch (Exception e) {
            log.error("[WeatherAgent] 天气分析失败", e);
            return "⚠️ 天气分析 Agent 暂时不可用：" + e.getMessage();
        }
    }
}
