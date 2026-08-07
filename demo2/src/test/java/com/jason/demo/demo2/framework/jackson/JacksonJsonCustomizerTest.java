package com.jason.demo.demo2.framework.jackson;

import com.jason.demo.demo2.order.repository.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonJsonCustomizerTest {

    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonJsonCustomizer().longAndDateTimeJsonCustomizer().customize(builder);
        jsonMapper = builder.build();
    }

    @Test
    void longAndBigInteger_writeAsJsonString() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", 2085550503315509248L);
        body.put("big", new BigInteger("2085550503315509248"));
        body.put("amount", new BigDecimal("9.90"));
        body.put("count", 3);

        String json = jsonMapper.writeValueAsString(body);

        assertTrue(json.contains("\"orderId\":\"2085550503315509248\""));
        assertTrue(json.contains("\"big\":\"2085550503315509248\""));
        assertTrue(json.contains("\"amount\":9.90") || json.contains("\"amount\":9.9"));
        assertTrue(json.contains("\"count\":3"));
        assertFalse(json.contains("\"orderId\":2085550503315509248"));
    }

    @Test
    void localDateTimeAndInstant_useConfiguredPattern() {
        OrderEntity order = new OrderEntity();
        order.setOrderId(55L);
        order.setStatus("PAID");
        order.setAmount(new BigDecimal("1.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 8, 7, 10, 16, 2));
        order.setUpdatedAt(LocalDateTime.of(2026, 8, 7, 10, 16, 2));

        String orderJson = jsonMapper.writeValueAsString(order);
        assertTrue(orderJson.contains("\"orderId\":\"55\""));
        assertTrue(orderJson.contains("\"createdAt\":\"2026-08-07 10:16:02\""));
        assertTrue(orderJson.contains("\"updatedAt\":\"2026-08-07 10:16:02\""));

        Instant instant = LocalDateTime.of(2026, 8, 7, 10, 16, 2)
                .atZone(JacksonJsonCustomizer.ZONE)
                .toInstant();
        String instantJson = jsonMapper.writeValueAsString(Map.of("at", instant));
        assertTrue(instantJson.contains("\"at\":\"2026-08-07 10:16:02\""));
    }
}
