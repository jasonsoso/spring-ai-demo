package com.jason.demo.demo2.mcp.client.config;

import com.jason.demo.demo2.mcp.client.LkCoffeeTokenResolver;
import com.jason.demo.demo2.mcp.client.McpConnection;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 生命周期：local-server 同步按需初始化；远程连接在应用就绪后后台最后尝试，失败不阻塞启动。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true", matchIfMissing = true)
public class McpClientLifecycle {

    private static final String AMAP_ENV_KEY = "AMAP_API_KEY";

    private final List<McpSyncClient> mcpSyncClients;
    private final LkCoffeeTokenResolver tokenResolver;
    private final String amapConnectionUrl;
    private final String amapConnectionEndpoint;
    private final Set<McpConnection> initializedConnections = ConcurrentHashMap.newKeySet();

    public McpClientLifecycle(
            List<McpSyncClient> mcpSyncClients,
            LkCoffeeTokenResolver tokenResolver,
            @Value("${spring.ai.mcp.client.streamable-http.connections.amap.url:}") String amapConnectionUrl,
            @Value("${spring.ai.mcp.client.streamable-http.connections.amap.endpoint:}") String amapConnectionEndpoint) {
        this.mcpSyncClients = mcpSyncClients;
        this.tokenResolver = tokenResolver;
        this.amapConnectionUrl = amapConnectionUrl;
        this.amapConnectionEndpoint = amapConnectionEndpoint;
    }

    /**
     * 应用完全就绪后，在后台最后尝试连接远程 MCP；失败仅 WARN，不阻塞启动。
     */
    public void initializeRemoteConnectionsAsync() {
        Thread.startVirtualThread(() -> {
            log.info("[MCP Client] 应用已就绪，后台初始化远程 MCP（lkcoffee、amap）...");
            for (McpConnection connection : McpConnection.remoteConnections()) {
                initializeRemoteConnection(connection);
            }
            log.info("[MCP Client] 远程 MCP 后台初始化流程结束");
        });
    }

    /**
     * 按需初始化指定连接（如首次对话前）。
     */
    public void ensureInitialized(McpConnection connection) {
        if (initializedConnections.contains(connection)) {
            return;
        }
        Optional<String> skipReason = skipReason(connection, true);
        if (skipReason.isPresent()) {
            log.debug("[MCP Client] {} 尚未初始化：{}", connection.getConnectionName(), skipReason.get());
            return;
        }
        synchronized (this) {
            if (initializedConnections.contains(connection)) {
                return;
            }
            findClient(connection).ifPresent(client -> initializeClient(client, connection, false));
        }
    }

    public void ensureLkCoffeeAndAmapIfConfigured() {
        for (McpConnection connection : McpConnection.remoteConnections()) {
            ensureInitialized(connection);
        }
    }

    private void initializeRemoteConnection(McpConnection connection) {
        if (initializedConnections.contains(connection)) {
            return;
        }
        Optional<String> skipReason = skipReason(connection, false);
        if (skipReason.isPresent()) {
            log.info("[MCP Client] 跳过 {} 后台初始化：{}", connection.getConnectionName(), skipReason.get());
            return;
        }
        synchronized (this) {
            if (initializedConnections.contains(connection)) {
                return;
            }
            findClient(connection).ifPresent(client -> initializeClient(client, connection, true));
        }
    }

    private void initializeClient(McpSyncClient client, McpConnection connection, boolean background) {
        String connectionName = connection.getConnectionName();
        boolean lkCoffeeInit = connection == McpConnection.LKCOFFEE;
        try {
            log.info("[MCP Client] 开始初始化 {}，clientInfo={}", connectionName, client.getClientInfo());
            client.initialize();
            initializedConnections.add(connection);
            log.info("[MCP Client] {} 初始化成功，工具: {}",
                    connectionName,
                    client.listTools().tools().stream().map(McpSchema.Tool::name).toList());
        } catch (Exception e) {
            if (isAuthorizationError(e)) {
                if (lkCoffeeInit) {
                    logAuthFailureDiagnostics(connectionName, e);
                }
                if (background) {
                    log.warn("[MCP Client] {} 后台初始化失败（鉴权）: {}。请检查 Token/Key 是否有效",
                            connectionName, e.getMessage(), e);
                } else {
                    log.error("[MCP Client] {} 初始化失败（鉴权）: {}。请检查 Token/Key 是否有效",
                            connectionName, e.getMessage(), e);
                }
            } else if (isTimeoutError(e)) {
                logTimeoutFailureDiagnostics(connectionName, e);
                if (background) {
                    log.warn("[MCP Client] {} 后台初始化失败（超时）: {}。请检查 spring.ai.mcp.client.request-timeout 与网络/VPN/防火墙",
                            connectionName, e.getMessage(), e);
                } else {
                    log.error("[MCP Client] {} 初始化失败（超时）: {}。请检查 spring.ai.mcp.client.request-timeout 与网络/VPN/防火墙",
                            connectionName, e.getMessage(), e);
                }
            } else if (background) {
                if (lkCoffeeInit) {
                    logLkCoffeeFailureDiagnostics(connectionName, e);
                }
                log.warn("[MCP Client] {} 后台初始化失败: {}", connectionName, e.getMessage(), e);
            } else {
                log.error("[MCP Client] {} 初始化失败: {}", connectionName, e.getMessage(), e);
            }
        }
    }

    private void logAuthFailureDiagnostics(String connectionName, Exception error) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String token = tokenResolver.resolveDefault();
        Integer httpStatus = authorizationHttpStatus(error);
        McpHttpClientTransportAuthorizationException authEx = authorizationException(error);
        String requestHeaders = authEx != null
                ? LkCoffeeMcpTransportConfig.formatHeaders(authEx.getRequestSnapshot().headers())
                : "";
        int authHeaderCount = authEx != null
                ? authEx.getRequestSnapshot().headers().allValues("Authorization").size()
                : 0;
        String hint = authHeaderCount == 0
                ? "Transport 未携带 Authorization，请检查 httpRequestCustomizer 是否生效"
                : "Transport 已携带 Authorization 但仍被拒绝，Token 可能已过期，请前往 https://open.lkcoffee.com/mcp 重新获取";
        log.debug("[MCP Client] {} 鉴权失败诊断: tokenSource={}, normalizedLength={}, configured={}, httpStatus={}, "
                        + "transportAuthHeaderCount={}. {}\n--- request headers (snapshot) ---\n{}",
                connectionName,
                tokenResolver.defaultTokenSource(),
                token.length(),
                StringUtils.hasText(token),
                httpStatus != null ? httpStatus : "unknown",
                authHeaderCount,
                hint,
                requestHeaders);
    }

    private static McpHttpClientTransportAuthorizationException authorizationException(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof McpHttpClientTransportAuthorizationException authEx) {
                return authEx;
            }
        }
        return null;
    }

    private static Integer authorizationHttpStatus(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof McpHttpClientTransportAuthorizationException authEx) {
                return authEx.getResponseInfo().statusCode();
            }
        }
        return null;
    }

    private void logLkCoffeeFailureDiagnostics(String connectionName, Exception error) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String token = tokenResolver.resolveDefault();
        log.debug("[MCP Client] {} 初始化失败诊断: tokenSource={}, normalizedLength={}, rootCause={}",
                connectionName,
                tokenResolver.defaultTokenSource(),
                token.length(),
                rootCauseMessage(error));
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private Optional<McpSyncClient> findClient(McpConnection connection) {
        return mcpSyncClients.stream()
                .filter(client -> connection.getConnectionName().equals(connectionName(client)))
                .findFirst();
    }

    private Optional<String> skipReason(McpConnection connection, boolean honorRequestToken) {
        return switch (connection) {
            case LKCOFFEE -> {
                if (!StringUtils.hasText(resolveLkCoffeeToken())) {
                    yield Optional.of("未配置 LKCOFFEE_TOKEN（请设置环境变量后重启）");
                }
                yield Optional.empty();
            }
            case AMAP -> {
                if (!StringUtils.hasText(resolveAmapApiKey())) {
                    yield Optional.of("未配置 AMAP_API_KEY 环境变量");
                }
                yield Optional.empty();
            }
            case LOCAL_SERVER -> {
                if (!honorRequestToken) {
                    yield Optional.of("本地 MCP 按需初始化（打开 MCP Tab 时连接）");
                }
                yield Optional.empty();
            }
        };
    }

    private String resolveLkCoffeeToken() {
        return tokenResolver.resolveDefault();
    }

    private String resolveAmapApiKey() {
        String fromEnv = System.getenv(AMAP_ENV_KEY);
        if (StringUtils.hasText(fromEnv)) {
            return fromEnv.trim();
        }
        String fromEndpoint = extractQueryParam(amapConnectionEndpoint, "key");
        if (StringUtils.hasText(fromEndpoint)) {
            return fromEndpoint;
        }
        return extractQueryParam(amapConnectionUrl, "key");
    }

    private static String extractQueryParam(String source, String paramName) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        String prefix = paramName + "=";
        int paramIndex = source.indexOf(prefix);
        if (paramIndex < 0) {
            return "";
        }
        String value = source.substring(paramIndex + prefix.length());
        int amp = value.indexOf('&');
        if (amp >= 0) {
            value = value.substring(0, amp);
        }
        return value.trim();
    }

    static String connectionName(McpSyncClient client) {
        McpSchema.Implementation info = client.getClientInfo();
        if (info != null && StringUtils.hasText(info.title())) {
            return info.title();
        }
        if (info != null && StringUtils.hasText(info.name())) {
            String name = info.name();
            int idx = name.lastIndexOf(" - ");
            return idx >= 0 ? name.substring(idx + 3) : name;
        }
        return "unknown";
    }

    private void logTimeoutFailureDiagnostics(String connectionName, Exception error) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("[MCP Client] {} 初始化超时诊断: rootCause={}。远程 MCP 初始化超时，"
                        + "请确认 spring.ai.mcp.client.request-timeout 已同步到 initializationTimeout，"
                        + "并检查本机到远程 MCP 的网络连通性",
                connectionName,
                rootCauseMessage(error));
    }

    private static boolean isTimeoutError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAuthorizationError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof McpHttpClientTransportAuthorizationException) {
                return true;
            }
        }
        return false;
    }
}
