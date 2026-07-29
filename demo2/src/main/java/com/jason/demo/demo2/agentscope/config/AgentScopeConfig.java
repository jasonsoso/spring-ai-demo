package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscopea2a.client.RiskReviewTool;
import com.jason.demo.demo2.agentscope.mcp.AgentscopeMcpClientRegistry;
import com.jason.demo.demo2.agentscope.middleware.AgentExecutionLoggingMiddleware;
import com.jason.demo.demo2.agentscope.state.PathSafeAgentStateStore;
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
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import org.springframework.beans.factory.ObjectProvider;
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

    /** Plan Mode / TaskList：不改业务代码的工具，默认放行（不含 plan_exit） */
    static final List<String> PLAN_AUTO_APPROVED_TOOL_NAMES =
            List.of("plan_enter", "plan_write", "todo_write");

    /** 沙箱规划阶段只读文件工具，默认放行 */
    static final List<String> SANDBOX_PLAN_READ_TOOL_NAMES =
            List.of("list_files", "glob_files", "grep_files");

    /** 沙箱工作区投影根（含 plans） */
    static List<String> sandboxWorkspaceProjectionRoots() {
        return List.of(
                "AGENTS.md",
                "skills",
                "subagents",
                "knowledge",
                ".skills-cache",
                "plans",
                "project");
    }

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

    /** 组装 Docker 沙箱文件系统规格（SESSION 隔离 + 本地快照）。 */
    static DockerFilesystemSpec dockerFilesystemSpec(DevAgentProperties properties) {
        DevAgentProperties.Sandbox config = properties.sandbox();
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot(config.workspaceRoot());

        Path snapshotPath = Path.of(properties.projectRoot()).resolve(config.snapshotRoot()).normalize();

        DockerFilesystemSpec filesystem = new DockerFilesystemSpec()
                .image(config.image())
                .network(config.network())
                .workspaceRoot(config.workspaceRoot())
                .memorySizeBytes(config.memorySizeBytes())
                .cpuCount(config.cpuCount())
                .snapshotSpec(new LocalSnapshotSpec(snapshotPath))
                .workspaceSpec(workspace);

        filesystem.isolationScope(IsolationScope.SESSION);
        filesystem.workspaceProjectionRoots(sandboxWorkspaceProjectionRoots());
        return filesystem;
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

    /**
     * 会话状态存储。沙箱开启时包一层 PathSafe，与 HarnessAgent 使用同一实例，
     * 以便 HITL confirm 能读写含 {@code /} 的 sandbox sessionId。
     */
    @Bean
    AgentStateStore agentscopeAgentStateStore(
            AgentscopeDistributedBackend backend,
            DevAgentProperties properties) {
        AgentStateStore base = backend.stateStore();
        if (properties.sandbox().enabled()) {
            return new PathSafeAgentStateStore(base);
        }
        return base;
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
     * 沙箱开：DockerFilesystemSpec + PathSafe stateStore；关：现状（可选 RemoteFilesystem）。
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
            AgentStateStore agentscopeAgentStateStore,
            AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry,
            ObjectProvider<RiskReviewTool> riskReviewTools) throws IOException {
        return buildAgentscopeDevAgent(
                agentscopeDeepSeekModel,
                properties,
                agentscopeCompactionConfig,
                agentscopeMemoryConfig,
                projectInfoTools,
                fileChangeTool,
                agentscopeDistributedBackend,
                agentscopeAgentStateStore,
                agentExecutionLoggingMiddleware,
                agentscopeMcpClientRegistry,
                riskReviewTools.getIfAvailable());
    }

    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            CompactionConfig agentscopeCompactionConfig,
            MemoryConfig agentscopeMemoryConfig,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool,
            AgentscopeDistributedBackend agentscopeDistributedBackend,
            AgentStateStore agentscopeAgentStateStore,
            AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) throws IOException {
        return buildAgentscopeDevAgent(
                agentscopeDeepSeekModel,
                properties,
                agentscopeCompactionConfig,
                agentscopeMemoryConfig,
                projectInfoTools,
                fileChangeTool,
                agentscopeDistributedBackend,
                agentscopeAgentStateStore,
                agentExecutionLoggingMiddleware,
                agentscopeMcpClientRegistry,
                null);
    }

    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            CompactionConfig agentscopeCompactionConfig,
            MemoryConfig agentscopeMemoryConfig,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool,
            AgentscopeDistributedBackend agentscopeDistributedBackend,
            AgentStateStore agentscopeAgentStateStore,
            AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry,
            RiskReviewTool riskReviewTool) throws IOException {
        return buildAgentscopeDevAgent(
                agentscopeDeepSeekModel,
                properties,
                agentscopeCompactionConfig,
                agentscopeMemoryConfig,
                projectInfoTools,
                fileChangeTool,
                agentscopeDistributedBackend,
                agentscopeAgentStateStore,
                agentExecutionLoggingMiddleware,
                agentscopeMcpClientRegistry,
                riskReviewTool);
    }

    private HarnessAgent buildAgentscopeDevAgent(
            Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            CompactionConfig agentscopeCompactionConfig,
            MemoryConfig agentscopeMemoryConfig,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool,
            AgentscopeDistributedBackend agentscopeDistributedBackend,
            AgentStateStore agentscopeAgentStateStore,
            AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry,
            RiskReviewTool riskReviewTool) throws IOException {
        String systemPrompt = properties.systemPrompt()
                .replace("{mcpRoot}", AgentscopeMcpClientRegistry.primaryMcpRootDisplay(properties));
        DevAgentProperties.Sandbox sandbox = properties.sandbox();
        if (sandbox.enabled()) {
            systemPrompt += """

                    【沙箱硬约束】
                    当前处于 Docker Sandbox 模式。代码和测试只能在沙箱项目目录执行：
                    - execute 的 working_directory 必须严格填写 `project`，禁止填写宿主机绝对路径。
                    - read_file/edit_file 的 path 必须是相对于 `project` 的路径。
                    - 规划阶段可用 read_file / list_files / glob_files / grep_files 与 plan_* / todo_write；
                      执行阶段改代码必须 edit_file，验证用 execute；不要用 Shell 重定向、sed、perl、python 等绕过文件工具。
                    - 每次回复最多调用一个沙箱工具；必须等待工具结果后再调用下一个工具，禁止并行调用多个沙箱工具。

                    【Plan Mode】
                    用户要求「先写计划再修复」「先调查再改代码」「进入 Plan Mode」或明确要求先规划时：
                    1. 第一项工具调用必须是 plan_enter；
                    2. 进入后只做只读调查：优先 read_file / list_files / glob_files / grep_files；
                    3. 规划阶段不要调用 execute、edit_file、write_file，也不要创建子 Agent 或调用 MCP 工具；
                    4. 调查完成后调用 plan_write，把完整计划写入 plans/PLAN.md（目标、已确认事实、拟改文件、步骤、验证、风险）；不要编造未查到的结论；
                    5. 写完计划后立刻调用 plan_exit，等待用户确认；
                    6. 用户批准后进入执行：先用 todo_write 按计划建任务列表，始终只保留一个 in_progress；
                    7. 执行阶段项目位于 /workspace/project：先读已批准计划；改已有文件必须 edit_file；execute 仅用于构建/测试/查看环境；working_directory 用工作区相对路径 project，不要在 command 里 cd；若计划依据不成立则停止并重新规划；
                    8. 最终说明计划文件、批准结果、修改文件和测试结果。
                    """;
        }
        Toolkit toolkit = new Toolkit();
        if (riskReviewTool != null) {
            toolkit.registerTool(riskReviewTool);
        }
        if (!sandbox.enabled()) {
            toolkit.registerTool(projectInfoTools);
            toolkit.registerAgentTool(fileChangeTool);
            for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
                toolkit.registerTool(entry.tools());
            }
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
                .disableAtPathExpansion()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .enablePlanMode()
                .enableTaskList();

        if (sandbox.enabled()) {
            // Compaction 会在工具轮次之间读 workspace，但此时 Docker sandbox 已释放 call context，
            // 会抛 SandboxConfigurationException 并打断后续推理；沙箱演示先关 compaction。
            builder.stateStore(agentscopeAgentStateStore)
                    .filesystem(dockerFilesystemSpec(properties))
                    .disableCompaction()
                    // Memory flush/maintenance 会在工具调用结束后异步访问 workspace，
                    // 此时 SandboxLifecycleMiddleware 已释放容器，导致 No active sandbox。
                    .disableMemoryTools()
                    .disableMemoryHooks()
                    // WorkspaceContextMiddleware 同样会在每轮推理重读 workspace；
                    // 沙箱生命周期只覆盖工具调用，避免在容器释放后访问文件系统。
                    .disableWorkspaceContext();
        } else {
            builder.compaction(agentscopeCompactionConfig)
                    .disableFilesystemTools()
                    .disableShellTool();
            if (agentscopeDistributedBackend instanceof AgentscopeDistributedBackend.Remote remote) {
                builder.distributedStore(remote.distributedStore())
                        .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER));
            } else {
                builder.stateStore(agentscopeAgentStateStore);
            }
        }

        if (properties.memory().enabled() && !sandbox.enabled()) {
            builder.memory(agentscopeMemoryConfig);
        } else {
            builder.disableMemoryTools().disableMemoryHooks();
        }
        HarnessAgent agent = builder.build();
        agent.getToolkit().removeTool("wait_async_results");
        if (sandbox.enabled()) {
            agent.getToolkit().removeTool("write_file");
        }
        return agent;
    }

    /** 组装工具权限：只读 / 子 Agent / MCP / 记忆 / Plan Mode / 沙箱只读工具的允许规则 */
    static PermissionContextState permissionContext(
            DevAgentProperties properties,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        READ_ONLY_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        SUBAGENT_COLLAB_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        PLAN_AUTO_APPROVED_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        builder.addAllowRule("risk_review", allowRule("risk_review"));
        for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
            for (String toolName : entry.enabledTools()) {
                builder.addAllowRule(toolName, allowRule(toolName));
            }
        }
        applyMemoryAllowRules(builder, properties.memory());
        if (properties.sandbox().enabled()) {
            builder.addAllowRule("read_file", allowRule("read_file"));
            SANDBOX_PLAN_READ_TOOL_NAMES.forEach(
                    toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
            // execute / edit_file / plan_exit 走 HITL，不自动放行
        }
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
