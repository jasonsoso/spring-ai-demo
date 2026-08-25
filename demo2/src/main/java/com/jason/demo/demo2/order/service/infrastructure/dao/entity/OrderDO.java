package com.jason.demo.demo2.order.service.infrastructure.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("demo_order")
public class OrderDO {

    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;
    private Long memberId;
    private String status;
    private BigDecimal amount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
