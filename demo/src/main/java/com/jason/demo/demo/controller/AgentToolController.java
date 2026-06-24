package com.jason.demo.demo.controller;

import com.jason.demo.demo.service.ToolTripAgentService;
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
 * 带工具调用的 Agent 接口
 * 提供天气查询 + 景点推荐 + 行程规划的一站式能力
 */
@Tag(name = "Agent Tools", description = "带工具调用能力的智能行程规划 Agent（DeepSeek + @Tool 注解）")
@RestController
@RequestMapping("/agent/tool")
@RequiredArgsConstructor
public class AgentToolController {

    private final ToolTripAgentService toolTripAgentService;

    /**
     * 带工具调用的行程规划接口
     *
     * @param demand 用户出行需求
     * @return 智能行程规划结果（自动调用天气、景点工具）
     */
    @Operation(summary = "带工具调用的行程规划",
            description = "Agent 自动判断调用天气查询工具和景点推荐工具，生成结合实时数据的个性化行程")
    @GetMapping("/plan")
    public Map<String, String> planTrip(
            @Parameter(description = "出行需求，可包含天气查询和景点推荐", example = "帮我规划北京周末游，先看看天气，再推荐几个人文景点")
            @RequestParam("demand") String demand) {
        String tripPlan = toolTripAgentService.planTripWithTools(demand);
        return Map.of(
                "userDemand", demand,
                "tripPlan", tripPlan,
                "agentType", "带工具调用能力的智能行程规划 Agent"
        );
    }
}
