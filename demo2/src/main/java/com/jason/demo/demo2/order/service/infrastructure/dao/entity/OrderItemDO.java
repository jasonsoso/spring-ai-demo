package com.jason.demo.demo2.order.service.infrastructure.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("demo_order_item")
public class OrderItemDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long itemId;
    private Long orderId;
    private Long memberId;
    private Long productId;
    private String productName;
    private String subtitle;
    private String coverUrl;
    private BigDecimal sellPrice;
    private BigDecimal marketPrice;
    private Integer qty;
    private LocalDateTime createdAt;
}
