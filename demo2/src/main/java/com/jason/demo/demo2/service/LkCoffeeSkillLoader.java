package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class LkCoffeeSkillLoader {

    private static final String OVERRIDE_RULES = """

            【demo2 项目覆盖规则 — 优先级高于 Skill 默认行为】

            1. Token 管理：
               - 使用 Tab 设置区 / 请求体 token / 环境变量 LKCOFFEE_TOKEN。
               - 禁止读写 ~/.my-coffee/LUCKIN_MCP_TOKEN 本地文件。
               - 禁止询问用户是否保存 token 到本地文件。

            2. MCP 调用方式：
               - 仅通过 Spring AI 已挂载的 MCP 工具调用，禁止 curl 直连 MCP HTTP。
               - 忽略 Skill 中「curl 调用 MCP」相关章节。

            3. 下单确认（强制）：
               - previewOrder 完成后，必须展示价格明细并等待用户明确回复「确认下单」等肯定语，
                 才允许调用 createOrder。
               - 覆盖 Skill 中「价格不涨则不再询问、直接 createOrder」的规则。

            4. 定位：
               - 优先使用前端请求附带的 longitude/latitude（见用户消息中的坐标上下文）。
               - 用户给地址时，调用高德 MCP 地理编码工具（非 IP 定位）。

            5. 门店选择：
               - queryShopList 后必须让用户从返回列表中确认门店，禁止自动选最近一家。
            """;

    private final String skillContent;

    public LkCoffeeSkillLoader(@Value("${agent.lkcoffee.skill}") Resource skillResource) throws IOException {
        this.skillContent = StreamUtils.copyToString(skillResource.getInputStream(), StandardCharsets.UTF_8);
        log.info("[LkCoffee] 已加载 My Coffee Skill，长度={} 字符", skillContent.length());
    }

    public String buildSystemPrompt() {
        return skillContent + "\n\n" + OVERRIDE_RULES;
    }
}
