package com.jason.demo.demo2.mcp.client;

import java.util.Arrays;
import java.util.Optional;

/**
 * Spring AI MCP streamable-http 连接名，与 {@code application.properties} 中
 * {@code spring.ai.mcp.client.streamable-http.connections.{name}} 配置键一致。
 */
public enum McpConnection {

    LOCAL_SERVER("local-server"),
    LKCOFFEE("lkcoffee"),
    AMAP("amap");

    private final String connectionName;

    McpConnection(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public static Optional<McpConnection> fromConnectionName(String connectionName) {
        return Arrays.stream(values())
                .filter(connection -> connection.connectionName.equals(connectionName))
                .findFirst();
    }

    /** 应用就绪后后台尝试初始化的远程连接 */
    public static McpConnection[] remoteConnections() {
        return new McpConnection[]{LKCOFFEE, AMAP};
    }
}
