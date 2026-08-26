package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.app.convert.ProductVoConvert;
import com.jason.demo.demo2.product.app.executor.ProductGetCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductListCmdExe;
import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListItemResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListResVO;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCmdExeTest {

    @Mock
    private ProductDomainService productDomainService;
    @Mock
    private ProductVoConvert productVoConvert;

    @InjectMocks
    private ProductListCmdExe productListCmdExe;

    @Test
    void listProducts_mapsItems() {
        ProductWithStock row = sampleRow();
        ProductListItemResVO item = new ProductListItemResVO();
        item.setProductId(9001L);
        item.setProductName("拿铁");
        when(productDomainService.listOnShelf()).thenReturn(List.of(row));
        when(productVoConvert.toListItem(row)).thenReturn(item);

        ProductListResVO result = productListCmdExe.execute();

        assertEquals(1, result.getItems().size());
        assertEquals("拿铁", result.getItems().get(0).getProductName());
    }

    @Test
    void getProduct_mapsDetail() {
        ProductGetCmdExe getCmdExe = new ProductGetCmdExe(productDomainService, productVoConvert);
        ProductWithStock row = sampleRow();
        ProductDetailResVO detail = new ProductDetailResVO();
        detail.setProductId(9001L);
        detail.setDetailContent("详情");
        when(productDomainService.requireOnShelf(9001L)).thenReturn(row);
        when(productVoConvert.toDetail(row)).thenReturn(detail);

        ProductDetailResVO result = getCmdExe.execute(9001L);

        assertEquals("详情", result.getDetailContent());
    }

    private static ProductWithStock sampleRow() {
        Product product = new Product();
        product.setProductId(9001L);
        product.setProductName("拿铁");
        product.setSellPrice(new BigDecimal("18.00"));
        ProductStock stock = new ProductStock();
        stock.setStock(100);
        stock.setSellStock(128);
        return new ProductWithStock(product, stock);
    }
}
