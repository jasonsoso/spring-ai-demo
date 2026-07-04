package com.jason.demo.demo2.mcp.client.config;

import com.jason.demo.demo2.mcp.client.LkCoffeeTokenContext;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeMcpConfig {

    private static final Set<String> LKCOFFEE_TOOL_SUFFIXES = Set.of(
            "queryShopList", "searchProductForMcp", "queryProductDetailInfo", "switchProduct",
            "previewOrder", "createOrder", "queryOrderDetailInfo", "cancelOrder");

    private static final Set<String> AMAP_GEO_TOOL_SUFFIXES = Set.of(
            "geocode", "reverse_geocode", "maps_geo", "maps_regeocode");

    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> lkCoffeeStreamableHttpCustomizer() {
        return (name, builder) -> {
            if (!"lkcoffee".equals(name)) {
                return;
            }
            builder.httpRequestCustomizer((requestBuilder, connectionName, uri, body, ctx) -> {
                String token = LkCoffeeTokenContext.get();
                if (token != null && !token.isBlank()) {
                    requestBuilder.header("Authorization", "Bearer " + token);
                }
            });
        };
    }

    public static boolean isAllowedTool(String prefixedName) {
        String lower = prefixedName.toLowerCase();
        return LKCOFFEE_TOOL_SUFFIXES.stream().anyMatch(lower::contains)
                || AMAP_GEO_TOOL_SUFFIXES.stream().anyMatch(lower::contains);
    }
}
