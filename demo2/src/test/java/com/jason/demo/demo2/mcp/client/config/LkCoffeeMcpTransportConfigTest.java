package com.jason.demo.demo2.mcp.client.config;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LkCoffeeMcpTransportConfigTest {

    @Test
    void formatHeaders_authorizationOnlyKeepsKeyLastSixCharacters() {
        HttpHeaders headers = headers(Map.of(
                "Authorization", List.of("Bearer 1234567890ABCDEF"),
                "Content-Type", List.of("application/json")));

        String formatted = LkCoffeeMcpTransportConfig.formatHeaders(headers);

        assertThat(formatted.lines())
                .containsExactlyInAnyOrder(
                        "Authorization: Bearer ******ABCDEF",
                        "Content-Type: application/json");
        assertThat(formatted).doesNotContain("1234567890ABCDEF");
    }

    @Test
    void formatHeaders_authorizationNameIsCaseInsensitive() {
        HttpHeaders headers = headers(Map.of(
                "authorization", List.of("Bearer abcdefghijkl")));

        String formatted = LkCoffeeMcpTransportConfig.formatHeaders(headers);

        assertThat(formatted).isEqualTo("authorization: Bearer ******ghijkl\n");
        assertThat(formatted).doesNotContain("abcdefghijkl");
    }

    @Test
    void formatHeaders_shortAuthorizationKeyIsFullyHidden() {
        HttpHeaders headers = headers(Map.of(
                "Authorization", List.of("Bearer 123456")));

        String formatted = LkCoffeeMcpTransportConfig.formatHeaders(headers);

        assertThat(formatted).isEqualTo("Authorization: Bearer ******\n");
        assertThat(formatted).doesNotContain("123456");
    }

    @Test
    void formatHeaders_authorizationWithoutSchemeStillOnlyKeepsLastSix() {
        HttpHeaders headers = headers(Map.of(
                "Authorization", List.of("123456789")));

        assertThat(LkCoffeeMcpTransportConfig.formatHeaders(headers))
                .isEqualTo("Authorization: ******456789\n");
    }

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (name, value) -> true);
    }
}
