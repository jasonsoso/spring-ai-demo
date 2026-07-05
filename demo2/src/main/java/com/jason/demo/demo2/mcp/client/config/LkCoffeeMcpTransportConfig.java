package com.jason.demo.demo2.mcp.client.config;

import com.jason.demo.demo2.mcp.client.LkCoffeeTokenResolver;
import com.jason.demo.demo2.mcp.client.McpConnection;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeMcpTransportConfig {

    private static final int DEBUG_BODY_MAX_LENGTH = 2048;

    private static final McpSchema.ClientCapabilities MINIMAL_CLIENT_CAPABILITIES =
            new McpSchema.ClientCapabilities(null, null, null, null);

    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> remoteMcpSyncClientCustomizer(
            @Value("${spring.ai.mcp.client.request-timeout:60s}") Duration requestTimeout) {
        return (name, spec) -> {
            if (McpConnection.LKCOFFEE.getConnectionName().equals(name)
                    || McpConnection.AMAP.getConnectionName().equals(name)) {
                spec.capabilities(MINIMAL_CLIENT_CAPABILITIES);
                spec.requestTimeout(requestTimeout);
                spec.initializationTimeout(requestTimeout);
            }
        };
    }

    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> lkCoffeeStreamableHttpCustomizer(
            LkCoffeeTokenResolver tokenResolver) {
        return (name, builder) -> {
            if (!McpConnection.LKCOFFEE.getConnectionName().equals(name)) {
                return;
            }
            log.info("[LkCoffee MCP] 注册 transport customizer（httpRequestCustomizer 按请求注入 Authorization）");
            builder.connectTimeout(Duration.ofSeconds(30));
            builder.customizeClient(clientBuilder -> clientBuilder.version(HttpClient.Version.HTTP_1_1));
            builder.httpRequestCustomizer((requestBuilder, method, uri, body, ctx) -> {
                String token = tokenResolver.resolveDefault();
                if (StringUtils.hasText(token)) {
                    requestBuilder.header("Authorization",
                            LkCoffeeTokenResolver.formatAuthorizationHeader(token));
                }
                logOutgoingRequest(method, uri, requestBuilder, body);
            });
        };
    }

    private static void logOutgoingRequest(String method, java.net.URI uri, HttpRequest.Builder requestBuilder,
            String body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        HttpRequest probe = requestBuilder.copy().build();
        String bodyLog = body != null && body.length() > DEBUG_BODY_MAX_LENGTH
                ? body.substring(0, DEBUG_BODY_MAX_LENGTH) + "..."
                : body;
        log.debug("[LkCoffee MCP] >>> {} {}\n--- headers ---\n{}--- body ---\n{}",
                method, uri, formatHeaders(probe.headers()), bodyLog);
    }

    static String formatHeaders(HttpHeaders headers) {
        if (headers == null) {
            return "";
        }
        StringBuilder headerDump = new StringBuilder();
        headers.map().forEach((name, values) ->
                values.forEach(value -> headerDump.append(name).append(": ").append(value).append('\n')));
        return headerDump.toString();
    }
}
