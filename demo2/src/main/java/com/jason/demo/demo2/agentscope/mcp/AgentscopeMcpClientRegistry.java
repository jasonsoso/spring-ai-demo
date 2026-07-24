package com.jason.demo.demo2.agentscope.mcp;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AgentscopeMcpClientRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeMcpClientRegistry.class);
    public static final String MCP_DISABLED_ROOT_PLACEHOLDER = "(MCP 未启用)";

    public record Entry(McpClientWrapper client, List<String> enabledTools) {
    }

    private final List<Entry> entries;

    private AgentscopeMcpClientRegistry(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public static AgentscopeMcpClientRegistry create(DevAgentProperties properties) {
        DevAgentProperties.McpSettings mcp = properties.mcp();
        if (!mcp.enabled()) {
            return new AgentscopeMcpClientRegistry(List.of());
        }

        List<Entry> created = new ArrayList<>();
        try {
            for (DevAgentProperties.McpClientConfig config : mcp.clients()) {
                if (!config.enabled()) {
                    continue;
                }
                List<String> arguments = new ArrayList<>(config.arguments());
                if (config.root() != null && !config.root().isBlank()) {
                    arguments.add(resolveRoot(properties.projectRoot(), config.root()).toString());
                }
                McpClientWrapper client = McpClientBuilder.create(config.name())
                        .stdioTransport(config.command(), arguments.toArray(String[]::new))
                        .buildAsync()
                        .block();
                created.add(new Entry(client, List.copyOf(config.enabledTools())));
                log.info("AgentScope MCP client ready: name={}, tools={}",
                        config.name(), config.enabledTools());
            }
        } catch (RuntimeException ex) {
            created.forEach(entry -> safeClose(entry.client()));
            throw ex;
        }
        return new AgentscopeMcpClientRegistry(created);
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
        for (Entry entry : entries) {
            safeClose(entry.client());
        }
    }

    private static void safeClose(McpClientWrapper client) {
        try {
            client.close();
        } catch (RuntimeException ex) {
            log.warn("Failed to close MCP client {}", client.getName(), ex);
        }
    }
}
