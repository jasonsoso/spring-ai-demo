package com.jason.demo.demo2.product.service.infrastructure.publisher;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockSyncEvent {
    private Long productId;
    private Long orderId;
    private String optType;
    private Integer qty;
    private String idempotentKey;
    /** Redis 本次 Lua 后的 seq；MySQL 必须从 seq-1 接到这条。 */
    private Long seq;
}
