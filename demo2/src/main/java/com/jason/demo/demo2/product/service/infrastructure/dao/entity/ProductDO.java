package com.jason.demo.demo2.product.service.infrastructure.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("demo_product")
public class ProductDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long productId;
    private String productName;
    private String subtitle;
    private String coverUrl;
    private BigDecimal sellPrice;
    private BigDecimal marketPrice;
    private String detailContent;
    private String status;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
