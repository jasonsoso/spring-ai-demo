package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.service.MultiAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 多 Agent 协作行程规划控制器
 * 文章：Spring AI 实战：多 Agent 协作实战 —— 分工拆解复杂旅游行程任务
 *
 * 分层结构：
 *   agent/  —— SupervisorAgent / ItineraryAgent / WeatherAgent / BudgetAgent
 *   tool/   —— TimeMethodTool（WeatherAgent 携带）/ CityRequest
 *   service/—— MultiAgentService（Supervisor-Worker 调度）
 *   controller/—— MultiAgentController（对外 HTTP 接口）
 *
 * Chat 模型：DeepSeek | Embedding：智谱 embedding-2（本接口无需调用）
 */
@Tag(name = "多 Agent 协作",
     description = "Supervisor-Worker 模式：SupervisorAgent 分解需求，"
             + "ItineraryAgent/WeatherAgent（携带 TimeMethodTool）/BudgetAgent 并行规划，"
             + "最终 Supervisor 综合输出（DeepSeek 驱动）")
@RestController
@RequestMapping("/agent/multi")
@RequiredArgsConstructor
public class MultiAgentController {

    private final MultiAgentService multiAgentService;

    /**
     * 多 Agent 协作旅游行程规划
     * 内部流程：
     *   1. SupervisorAgent.decompose()  分解需求（1 次 LLM）
     *   2. WeatherAgent / ItineraryAgent / BudgetAgent 并行执行（3 次 LLM，WeatherAgent 额外调用 TimeMethodTool）
     *   3. SupervisorAgent.synthesize() 综合输出（1 次 LLM）
     * 共 5 次 LLM 调用，预计耗时 20~50 秒，请耐心等待。
     */
    @Operation(
            summary = "多 Agent 协作旅游行程规划",
            description = """
                    Supervisor-Worker 多 Agent 协作模式（文章同款分层结构）：
                    1. SupervisorAgent — 分析需求，提炼关键要素（目的地/天数/人数/预算/偏好）
                    2. ItineraryAgent  — 逐日行程规划（景点动线+游览时长）
                       WeatherAgent   — 天气分析+穿搭建议（携带 TimeMethodTool 查询实时时区/季节）
                       BudgetAgent    — 预算分配方案（交通/住宿/餐饮/门票/备用金）
                       三路并行执行（CompletableFuture）
                    3. SupervisorAgent — 综合三路输出，生成完整可执行行程方案
                    Chat 走 DeepSeek；Embedding 走智谱 embedding-2（本接口无需调用）
                    """
    )
    @GetMapping("/plan")
    public Map<String, Object> plan(
            @Parameter(
                    description = "出行需求（目的地/天数/人数/预算/偏好等自然语言描述）",
                    example = "五一假期 4 天云南大理+丽江游，2 人，预算 8000，偏好自然风光和少数民族文化，不吃辣，交通以飞机+租车为主"
            )
            @RequestParam("demand") String demand) {
        return multiAgentService.plan(demand);
    }
}
