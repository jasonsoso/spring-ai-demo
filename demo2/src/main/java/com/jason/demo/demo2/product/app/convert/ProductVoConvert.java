package com.jason.demo.demo2.product.app.convert;

import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListItemResVO;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductVoConvert {

    @Mapping(target = "productId", source = "product.productId")
    @Mapping(target = "productName", source = "product.productName")
    @Mapping(target = "subtitle", source = "product.subtitle")
    @Mapping(target = "coverUrl", source = "product.coverUrl")
    @Mapping(target = "sellPrice", source = "product.sellPrice")
    @Mapping(target = "marketPrice", source = "product.marketPrice")
    @Mapping(target = "availableStock", source = "stock.stock")
    @Mapping(target = "sellStock", source = "stock.sellStock")
    ProductListItemResVO toListItem(ProductWithStock row);

    @Mapping(target = "productId", source = "product.productId")
    @Mapping(target = "productName", source = "product.productName")
    @Mapping(target = "subtitle", source = "product.subtitle")
    @Mapping(target = "coverUrl", source = "product.coverUrl")
    @Mapping(target = "sellPrice", source = "product.sellPrice")
    @Mapping(target = "marketPrice", source = "product.marketPrice")
    @Mapping(target = "availableStock", source = "stock.stock")
    @Mapping(target = "sellStock", source = "stock.sellStock")
    @Mapping(target = "detailContent", source = "product.detailContent")
    ProductDetailResVO toDetail(ProductWithStock row);
}
