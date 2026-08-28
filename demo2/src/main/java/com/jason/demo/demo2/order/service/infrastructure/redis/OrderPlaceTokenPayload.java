package com.jason.demo.demo2.order.service.infrastructure.redis;

import java.math.BigDecimal;
import java.util.List;

public record OrderPlaceTokenPayload(Long memberId, List<Item> items) {

    public record Item(Long productId, Integer qty, BigDecimal sellPrice) {
    }
}
