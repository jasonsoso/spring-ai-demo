package com.jason.demo.demo2.agentscope.mcp;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 使用 MCP Java SDK 2.0 创建 stdio Client，并把白名单工具包装为 {@link McpFilesystemTools}。
 * 不使用 AgentScope {@code McpClientBuilder}，以便与 Spring AI MCP（SDK 2.x）共存。
 */
public final class AgentscopeMcpClientRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeMcpClientRegistry.class);
    public static final String MCP_DISABLED_ROOT_PLACEHOLDER = "(MCP 未启用)";

    public record Entry(String clientName, McpFilesystemTools tools, List<String> enabledTools) {
    }

    private final List<Entry> entries;
    private final List<McpSyncClient> clients;

    private AgentscopeMcpClientRegistry(List<Entry> entries, List<McpSyncClient> clients) {
        this.entries = List.copyOf(entries);
        this.clients = List.copyOf(clients);
    }

    public List<Entry> entries() {
        return entries;
    }

    public static AgentscopeMcpClientRegistry create(DevAgentProperties properties) {
        DevAgentProperties.McpSettings mcp = properties.mcp();
        if (!mcp.enabled()) {
            return new AgentscopeMcpClientRegistry(List.of(), List.of());
        }

        List<Entry> createdEntries = new ArrayList<>();
        List<McpSyncClient> createdClients = new ArrayList<>();
        try {
            for (DevAgentProperties.McpClientConfig config : mcp.clients()) {
                if (!config.enabled()) {
                    continue;
                }
                List<String> arguments = new ArrayList<>(config.arguments());
                if (config.root() != null && !config.root().isBlank()) {
                    arguments.add(resolveRoot(properties.projectRoot(), config.root()).toString());
                }
                String command = config.command();
                List<String> processArgs = arguments;
                if (isWindows() && "npx".equalsIgnoreCase(command)) {
                    processArgs = new ArrayList<>();
                    processArgs.add("/c");
                    processArgs.add("npx");
                    processArgs.addAll(arguments);
                    command = "cmd.exe";
                }

                ServerParameters params = ServerParameters.builder(command)
                        .args(processArgs)
                        .build();
                StdioClientTransport transport = new StdioClientTransport(
                        params, new JacksonMcpJsonMapperSupplier().get());
                McpSyncClient client = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(120))
                        .initializationTimeout(Duration.ofSeconds(60))
                        .clientInfo(new McpSchema.Implementation(
                                "demo2-agentscope-" + config.name(), "1.0.0"))
                        .build();
                client.initialize();
                createdClients.add(client);

                Set<String> enabled = new HashSet<>(config.enabledTools());
                Set<String> available = new HashSet<>();
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    if (enabled.contains(tool.name())) {
                        available.add(tool.name());
                    }
                }
                if (available.isEmpty()) {
                    throw new IllegalStateException(
                            "MCP client '" + config.name()
                                    + "' initialized but no enabled tools matched: "
                                    + config.enabledTools());
                }
                McpFilesystemTools tools = new McpFilesystemTools(client, available);
                createdEntries.add(new Entry(config.name(), tools, List.copyOf(available)));
                log.info("AgentScope MCP client ready: name={}, tools={}",
                        config.name(), available);
            }
        } catch (RuntimeException ex) {
            createdClients.forEach(AgentscopeMcpClientRegistry::safeClose);
            throw ex;
        }
        return new AgentscopeMcpClientRegistry(createdEntries, createdClients);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static Path resolveRoot(String projectRoot, String configuredRoot) {
        Path configured = Path.of(configuredRoot);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return Path.of(projectRoot)
                .toAbsolutePath()
                .normalize()
                .resolve(configured)
                .normalize();
    }

    public static String primaryMcpRootDisplay(DevAgentProperties properties) {
        if (!properties.mcp().enabled()) {
            return MCP_DISABLED_ROOT_PLACEHOLDER;
        }
        for (DevAgentProperties.McpClientConfig config : properties.mcp().clients()) {
            if (config.enabled() && config.root() != null && !config.root().isBlank()) {
                return resolveRoot(properties.projectRoot(), config.root()).toString();
            }
        }
        return MCP_DISABLED_ROOT_PLACEHOLDER;
    }

    @Override
    public void close() {
        clients.forEach(AgentscopeMcpClientRegistry::safeClose);
    }

    private static void safeClose(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (RuntimeException ex) {
            log.warn("Failed to close MCP client", ex);
        }
    }
}
