package com.jason.demo.demo.service;

import com.jason.demo.demo.agent.BudgetAgent;
import com.jason.demo.demo.agent.ItineraryAgent;
import com.jason.demo.demo.agent.SupervisorAgent;
import com.jason.demo.demo.agent.WeatherAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 多 Agent 调度业务服务
 * 实现 Supervisor-Worker 模式：
 *   Step 1 — SupervisorAgent.decompose()：分解用户需求，生成任务摘要
 *   Step 2 — ItineraryAgent / WeatherAgent / BudgetAgent 三路并行执行（CompletableFuture）
 *   Step 3 — SupervisorAgent.synthesize()：整合三路结果，输出完整行程方案
 *
 * Chat：DeepSeek | Embedding：智谱 embedding-2（本服务无需调用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentService {

    private final SupervisorAgent  supervisorAgent;
    private final ItineraryAgent   itineraryAgent;
    private final WeatherAgent     weatherAgent;
    private final BudgetAgent      budgetAgent;

    /**
     * 多 Agent 协作旅游规划入口
     *
     * @param demand 用户旅游需求（自然语言，如目的地/天数/人数/预算/偏好）
     * @return 包含各 Agent 输出及最终整合方案的 Map
     */
    public Map<String, Object> plan(String demand) {
        long start = System.currentTimeMillis();
        log.info("[MultiAgent] ===== 开始多 Agent 协作规划 =====");
        log.info("[MultiAgent] 用户需求: {}", demand);

        // ---- Step 1: Supervisor 分解需求 ----
        String taskBrief = supervisorAgent.decompose(demand);
        log.info("[MultiAgent] Supervisor 分解完成");

        // ---- Step 2: 三个子 Agent 并行执行 ----
        final String agentInput = "【用户原始需求】\n" + demand
                + "\n\n【需求摘要（Supervisor 提炼）】\n" + taskBrief;

        log.info("[MultiAgent] 启动三个子 Agent 并行执行...");
        CompletableFuture<String> weatherFuture   = CompletableFuture.supplyAsync(
                () -> weatherAgent.analyze(agentInput));
        CompletableFuture<String> itineraryFuture = CompletableFuture.supplyAsync(
                () -> itineraryAgent.plan(agentInput));
        CompletableFuture<String> budgetFuture    = CompletableFuture.supplyAsync(
                () -> budgetAgent.allocate(agentInput));

        CompletableFuture.allOf(weatherFuture, itineraryFuture, budgetFuture).join();
        log.info("[MultiAgent] 三个子 Agent 全部完成，耗时: {}ms", System.currentTimeMillis() - start);

        String weatherAnalysis = weatherFuture.join();
        String itineraryPlan   = itineraryFuture.join();
        String budgetPlan      = budgetFuture.join();

        // ---- Step 3: Supervisor 整合输出 ----
        String finalPlan = supervisorAgent.synthesize(
                demand, taskBrief, weatherAnalysis, itineraryPlan, budgetPlan);

        long cost = System.currentTimeMillis() - start;
        log.info("[MultiAgent] ===== 多 Agent 协作完成，总耗时: {}ms =====", cost);

        Map<String, Object> result = new HashMap<>();
        result.put("userDemand",       demand);
        result.put("taskBrief",        taskBrief);
        result.put("weatherAnalysis",  weatherAnalysis);
        result.put("itineraryPlan",    itineraryPlan);
        result.put("budgetPlan",       budgetPlan);
        result.put("finalPlan",        finalPlan);
        result.put("totalCostMs",      cost);
        result.put("agentType",        "多Agent协作 · Supervisor-Worker · 天气/行程/预算并行+综合输出");
        return result;
    }
}
