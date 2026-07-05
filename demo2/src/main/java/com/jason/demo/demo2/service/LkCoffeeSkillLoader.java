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
               - 使用环境变量 LKCOFFEE_TOKEN（由服务端配置，用户无需提供）。
               - 禁止读写 ~/.my-coffee/LUCKIN_MCP_TOKEN 本地文件。
               - 禁止询问用户提供 token 或是否保存 token 到本地文件。

            2. MCP 调用方式：
               - 仅通过 Spring AI 已挂载的 MCP 工具调用，禁止 curl 直连 MCP HTTP。
               - 忽略 Skill 中「curl 调用 MCP」相关章节。

            3. 下单确认（强制，两阶段）：
               - 阶段一（preview 前）：用户确认门店与商品后，调用 previewOrder 获取最终价格。
               - 阶段二（preview 后）：previewOrder 工具已成功返回后，用户回复「确认下单」等肯定语时，
                 必须立即调用 createOrder，禁止再次展示预览、重复询问或仅输出文字总结。
               - 覆盖 Skill 中「preview 后不再询问、直接 createOrder」及「常见坑 #7 不要重复确认」的表述；
                 本项目 preview 后仍需用户二次确认，但二次确认后必须立刻下单。

            4. 定位：
               - 优先使用前端请求附带的 longitude/latitude（见用户消息中的坐标上下文）。
               - 用户给地址时，调用高德 MCP 地理编码工具（非 IP 定位）。

            5. 门店选择：
               - queryShopList 后必须让用户从返回列表中确认门店，禁止自动选最近一家。

            6. 回复展示格式：
               - 所有面向用户的回复内容使用标准 GFM Markdown（标题、列表、加粗、代码块、引用、表格等）。
               - 展示门店/商品列表时优先使用 Markdown 表格，每行必须以换行分隔，禁止用 || 拼接多行。
               - 列不宜过多，优先：序号、门店名称、地址、营业时间、距离。
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
