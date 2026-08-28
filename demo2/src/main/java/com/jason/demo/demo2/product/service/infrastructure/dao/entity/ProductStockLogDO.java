package com.jason.demo.demo2.product.service.infrastructure.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("demo_product_stock_log")
public class ProductStockLogDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long logId;
    private Long stockId;
    private Long productId;
    private Long orderId;
    private String optType;
    private String idempotentKey;
    private Integer changeQty;
    private Integer beforeActual;
    private Integer afterActual;
    private Integer beforeStock;
    private Integer afterStock;
    private Integer beforeWithhold;
    private Integer afterWithhold;
    private Integer beforeSell;
    private Integer afterSell;
    private String remarks;
    private LocalDateTime createdAt;
}
