package com.jason.demo.demo2.mcp.client.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    public static boolean isAllowedTool(String prefixedName) {
        String lower = prefixedName.toLowerCase();
        return LKCOFFEE_TOOL_SUFFIXES.stream().anyMatch(suffix -> lower.contains(suffix.toLowerCase()))
                || AMAP_GEO_TOOL_SUFFIXES.stream().anyMatch(suffix -> lower.contains(suffix.toLowerCase()));
    }
}
