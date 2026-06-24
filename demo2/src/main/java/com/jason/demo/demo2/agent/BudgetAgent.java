package com.jason.demo.demo2.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 预算规划子 Agent（Budget Agent）
 * 职责：根据总预算、人数及旅行天数，合理分配各项旅游开销，并给出省钱小技巧
 * Chat 模型：DeepSeek
 */
@Slf4j
@Component
public class BudgetAgent {

    private static final String BUDGET_SYSTEM = """
            你是专业的旅游预算规划专家（Budget Agent）。
            根据用户总预算、出行人数及天数，输出详细的费用分配方案：
            分配类目（每项给出区间值，单位：元/人 或 总计）：
            ① 交通费用：往返机票/火车票 + 当地交通（打车/地铁/租车）
            ② 住宿费用：按档次（经济/舒适/精品）推荐，含总价估算
            ③ 餐饮费用：按早/中/晚及人均分配，注明特色餐厅消费参考
            ④ 景点门票：逐一列出必游景点票价及预约要求
            ⑤ 购物/纪念品：建议预留比例
            ⑥ 应急备用金：建议预留 10% 左右
            最后给出 3 条省钱技巧（提前购票、优惠套票、峰值错峰等）。
            输出数据详实，格式表格化优先，直观易读。
            """;

    private final ChatClient chatClient;

    public BudgetAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone().defaultSystem(BUDGET_SYSTEM).build();
    }

    /**
     * 根据需求摘要规划预算分配
     *
     * @param agentInput 包含用户原始需求与 Supervisor 任务摘要的综合输入
     * @return 预算分配方案文本
     */
    public String allocate(String agentInput) {
        log.info("[BudgetAgent] 开始预算规划...");
        try {
            String result = chatClient.prompt()
                    .user("请根据以下旅游需求，输出详细的预算分配方案：\n" + agentInput)
                    .call()
                    .content();
            log.info("[BudgetAgent] 预算规划完成");
            return result;
        } catch (Exception e) {
            log.error("[BudgetAgent] 预算规划失败", e);
            return "⚠️ 预算规划 Agent 暂时不可用：" + e.getMessage();
        }
    }
}
