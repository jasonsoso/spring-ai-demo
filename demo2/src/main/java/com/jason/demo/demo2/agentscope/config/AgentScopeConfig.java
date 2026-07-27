package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.mcp.AgentscopeMcpClientRegistry;
import com.jason.demo.demo2.agentscope.middleware.AgentExecutionLoggingMiddleware;
import com.jason.demo.demo2.agentscope.tool.FileChangeTool;
import com.jason.demo.demo2.agentscope.tool.ProjectInfoTools;
import com.jason.demo.demo2.config.LoggingAgentscopeModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * AgentScope 开发 Agent 的 Spring 装配入口：模型、工具、权限、记忆与分布式后端。
 */
@Configuration
public class AgentScopeConfig {

    /** 只读项目信息工具，默认放行 */
    private static final List<String> READ_ONLY_TOOL_NAMES =
            List.of("read_pom", "list_source_folders", "find_main_class");

    /** 子 Agent 协作工具，默认放行 */
    private static final List<String> SUBAGENT_COLLAB_TOOL_NAMES =
            List.of("agent_spawn", "agent_send", "agent_list");

    /** 将配置属性转为上下文压缩配置（消息过多时摘要压缩） */
    static CompactionConfig toCompactionConfig(DevAgentProperties.Compaction c) {
        return CompactionConfig.builder()
                .triggerMessages(c.triggerMessages())
                .keepMessages(c.keepMessages())
                .keepTokens(0)
                .summaryPrompt(c.summaryPrompt())
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .build();
    }

    /** 将配置属性转为长期记忆配置（flush / consolidation） */
    static MemoryConfig toMemoryConfig(DevAgentProperties.Memory config) {
        return MemoryConfig.builder()
                .flushTrigger(MemoryConfig.FlushTrigger.throttled(config.flushMinGap()))
                .consolidationMinGap(config.consolidationMinGap())
                .consolidationMaxTokens(config.consolidationMaxTokens())
                .flushPrompt(config.flushPrompt())
                .consolidationPrompt(config.consolidationPrompt())
                .build();
    }

    @Bean
    CompactionConfig agentscopeCompactionConfig(DevAgentProperties properties) {
        return toCompactionConfig(properties.compaction());
    }

    @Bean
    MemoryConfig agentscopeMemoryConfig(DevAgentProperties properties) {
        return toMemoryConfig(properties.memory());
    }

    /** DeepSeek 对话模型，外包一层请求/响应日志 */
    @Bean
    @Qualifier("agentscopeDeepSeekModel")
    Model agentscopeDeepSeekModel(DevAgentProperties properties) {
        DevAgentProperties.Model model = properties.model();
        Model openAi = OpenAIChatModel.builder()
                .apiKey(model.apiKey() == null ? "" : model.apiKey())
                .baseUrl(model.baseUrl())
                .modelName(model.name())
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build();
        return new LoggingAgentscopeModel(openAi, "agentscope-deepseek");
    }

    /** 读取项目结构信息（pom、源码目录、主类等） */
    @Bean
    ProjectInfoTools projectInfoTools(DevAgentProperties properties) {
        return new ProjectInfoTools(Path.of(properties.projectRoot()));
    }

    /** 文件变更工具（写文件等需权限确认的能力） */
    @Bean
    FileChangeTool fileChangeTool(DevAgentProperties properties) {
        return new FileChangeTool(Path.of(properties.projectRoot()));
    }

    /** 本地 / 远程分布式存储后端（状态、文件系统等） */
    @Bean
    AgentscopeDistributedBackend agentscopeDistributedBackend(
            AgentscopeDistributedProperties distributedProperties,
            AgentScopeDataSourceProperties dataSourceProperties) {
        return AgentscopeDistributedBackendFactory.create(distributedProperties, dataSourceProperties);
    }

    @Bean
    AgentStateStore agentscopeAgentStateStore(AgentscopeDistributedBackend backend) {
        return backend.stateStore();
    }

    /** Agent 执行过程日志中间件 */
    @Bean
    AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware() {
        return new AgentExecutionLoggingMiddleware();
    }

    /** MCP 客户端注册表（应用关闭时自动 close） */
    @Bean(destroyMethod = "close")
    AgentscopeMcpClientRegistry agentscopeMcpClientRegistry(DevAgentProperties properties) {
        return AgentscopeMcpClientRegistry.create(properties);
    }

    /**
     * 主开发 Agent：组装模型、工具箱、工作区、权限与记忆。
     * 远程模式下走 distributedStore；本地模式只用 stateStore。
     */
    @Bean
    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            CompactionConfig agentscopeCompactionConfig,
            MemoryConfig agentscopeMemoryConfig,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool,
            AgentscopeDistributedBackend agentscopeDistributedBackend,
            AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) throws IOException {
        String systemPrompt = properties.systemPrompt()
                .replace("{mcpRoot}", AgentscopeMcpClientRegistry.primaryMcpRootDisplay(properties));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(projectInfoTools);
        toolkit.registerAgentTool(fileChangeTool);
        for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
            toolkit.registerTool(entry.tools());
        }
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(properties.name())
                .sysPrompt(systemPrompt)
                .model(agentscopeDeepSeekModel)
                .toolkit(toolkit)
                .workspace(Path.of(properties.workspaceRoot()))
                .permissionContext(permissionContext(properties, agentscopeMcpClientRegistry))
                .middleware(agentExecutionLoggingMiddleware)
                .enableAgentTracingLog(false)
                // 禁用内置文件系统 / Shell，改用自定义工具与 MCP
                .disableFilesystemTools()
                .disableShellTool()
                .compaction(agentscopeCompactionConfig)
                .disableAtPathExpansion()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig();
        if (agentscopeDistributedBackend instanceof AgentscopeDistributedBackend.Remote remote) {
            // 远程：分布式状态 + 按用户隔离的远程文件系统
            builder.distributedStore(remote.distributedStore())
                    .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER));
        } else {
            // 本地：仅挂载状态存储
            builder.stateStore(agentscopeDistributedBackend.stateStore());
        }
        if (properties.memory().enabled()) {
            builder.memory(agentscopeMemoryConfig);
        } else {
            builder.disableMemoryTools().disableMemoryHooks();
        }
        HarnessAgent agent = builder.build();
        // 移除不需要的异步等待工具
        agent.getToolkit().removeTool("wait_async_results");
        return agent;
    }

    /** 组装工具权限：只读 / 子 Agent / MCP / 记忆工具的允许规则 */
    private static PermissionContextState permissionContext(
            DevAgentProperties properties,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        READ_ONLY_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        SUBAGENT_COLLAB_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
            for (String toolName : entry.enabledTools()) {
                builder.addAllowRule(toolName, allowRule(toolName));
            }
        }
        applyMemoryAllowRules(builder, properties.memory());
        return builder.build();
    }

    /** 按记忆开关追加 memory_* 允许规则；save 可配置为需确认。package-visible for tests */
    static void applyMemoryAllowRules(
            PermissionContextState.Builder builder, DevAgentProperties.Memory memory) {
        if (!memory.enabled()) {
            return;
        }
        builder.addAllowRule("memory_search", allowRule("memory_search"));
        builder.addAllowRule("memory_get", allowRule("memory_get"));
        if (!memory.saveRequiresConfirm()) {
            builder.addAllowRule("memory_save", allowRule("memory_save"));
        }
    }

    /** 生成一条 ALLOW 权限规则 */
    private static PermissionRule allowRule(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "app");
    }
}
