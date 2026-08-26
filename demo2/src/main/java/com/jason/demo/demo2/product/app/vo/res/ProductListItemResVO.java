package com.jason.demo.demo2.product.app.vo.res;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductListItemResVO {
    private Long productId;
    private String productName;
    private String subtitle;
    private String coverUrl;
    private BigDecimal sellPrice;
    private BigDecimal marketPrice;
    private Integer availableStock;
    private Integer sellStock;
}
