package com.jason.demo.demo2.product.service.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStockDO> {

    int reserve(@Param("productId") long productId, @Param("qty") int qty);

    int confirm(@Param("productId") long productId, @Param("qty") int qty);

    int release(@Param("productId") long productId, @Param("qty") int qty);

    int adjustActual(@Param("productId") long productId, @Param("targetActual") int targetActual);

    int applyReserveDelta(@Param("productId") long productId, @Param("qty") int qty, @Param("seq") long seq);

    int applyConfirmDelta(@Param("productId") long productId, @Param("qty") int qty, @Param("seq") long seq);

    int applyReleaseDelta(@Param("productId") long productId, @Param("qty") int qty, @Param("seq") long seq);
}
