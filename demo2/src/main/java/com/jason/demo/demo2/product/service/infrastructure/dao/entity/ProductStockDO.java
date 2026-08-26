package com.jason.demo.demo2.product.service.infrastructure.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("demo_product_stock")
public class ProductStockDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long stockId;
    private Long productId;
    private Integer actualStock;
    private Integer stock;
    private Integer withholdStock;
    private Integer sellStock;
    private LocalDateTime updatedAt;
}
