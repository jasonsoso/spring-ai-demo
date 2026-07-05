package com.jason.demo.demo2.mcp.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 瑞幸 MCP Bearer Token 解析与规范化：env 优先于 property，统一剥离 Bearer 前缀与引号。
 */
@Component
public class LkCoffeeTokenResolver {

    public static final String ENV_TOKEN_KEY = "LKCOFFEE_TOKEN";

    private static final String BEARER_PREFIX = "Bearer ";

    private final String propertyToken;

    public LkCoffeeTokenResolver(@Value("${lkcoffee.token:}") String propertyToken) {
        this.propertyToken = propertyToken;
    }

    public enum DefaultTokenSource {
        ENV, PROPERTY, NONE
    }

    /**
     * 默认 Token：运行时 {@code LKCOFFEE_TOKEN} → {@code lkcoffee.token}。
     */
    public String resolveDefault() {
        String fromEnv = System.getenv(ENV_TOKEN_KEY);
        if (StringUtils.hasText(fromEnv)) {
            return normalize(fromEnv);
        }
        return normalize(propertyToken);
    }

    public DefaultTokenSource defaultTokenSource() {
        if (StringUtils.hasText(System.getenv(ENV_TOKEN_KEY))) {
            return DefaultTokenSource.ENV;
        }
        if (StringUtils.hasText(propertyToken)) {
            return DefaultTokenSource.PROPERTY;
        }
        return DefaultTokenSource.NONE;
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim().replace("\r", "").replace("\n", "");
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            token = token.substring(BEARER_PREFIX.length()).trim();
        }
        return token;
    }

    public static String formatAuthorizationHeader(String token) {
        String normalized = normalize(token);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return BEARER_PREFIX + normalized;
    }
}
