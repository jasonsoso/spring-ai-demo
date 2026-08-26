package com.jason.demo.demo2.product.service.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStockDO> {

    @Update("""
            UPDATE demo_product_stock
            SET stock = stock - #{qty}, withhold_stock = withhold_stock + #{qty}, updated_at = NOW(3)
            WHERE product_id = #{productId} AND stock >= #{qty}
            """)
    int reserve(@Param("productId") long productId, @Param("qty") int qty);

    @Update("""
            UPDATE demo_product_stock
            SET actual_stock = actual_stock - #{qty}, withhold_stock = withhold_stock - #{qty},
                sell_stock = sell_stock + #{qty}, updated_at = NOW(3)
            WHERE product_id = #{productId} AND withhold_stock >= #{qty}
            """)
    int confirm(@Param("productId") long productId, @Param("qty") int qty);

    @Update("""
            UPDATE demo_product_stock
            SET stock = stock + #{qty}, withhold_stock = withhold_stock - #{qty}, updated_at = NOW(3)
            WHERE product_id = #{productId} AND withhold_stock >= #{qty}
            """)
    int release(@Param("productId") long productId, @Param("qty") int qty);
}
