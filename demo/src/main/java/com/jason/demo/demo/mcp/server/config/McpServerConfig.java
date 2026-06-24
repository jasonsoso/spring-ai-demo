package com.jason.demo.demo.mcp.server.config;

import com.jason.demo.demo.tools.AttractionTool;
import com.jason.demo.demo.tools.WeatherTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 工具注册配置
 * 通过 List<SyncToolSpecification> bean 向 MCP Server 注册工具，
 * 自动配置会通过 ObjectProvider 注入，不与 servletMcpSyncServerCustomizer 冲突
 */
@Slf4j
@Configuration
public class McpServerConfig {

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcpSyncToolSpecifications(
            WeatherTool weatherTool,
            AttractionTool attractionTool) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(weatherTool, attractionTool)
                .build()
                .getToolCallbacks();
        List<McpServerFeatures.SyncToolSpecification> specs = McpToolUtils.toSyncToolSpecifications(callbacks);
        log.info("[MCP Server] 注册工具数量: {}", specs.size());
        return specs;
    }
}
