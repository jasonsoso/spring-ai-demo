package com.jason.demo.demo2.product.service.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<ProductDO> {
}
