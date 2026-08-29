package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.app.executor.OrderPreviewCmdExe;
import com.jason.demo.demo2.order.app.vo.req.OrderLineReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPreviewReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPreviewResVO;
import com.jason.demo.demo2.order.service.infrastructure.config.OrderProperties;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenPayload;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenStore;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPreviewCmdExeTest {
    @Mock ProductDomainService productDomainService;
    @Mock ProductStockHotService productStockHotService;
    @Mock OrderPlaceTokenStore tokenStore;
    OrderProperties orderProperties = new OrderProperties();

    @BeforeEach
    void login() {
        LoginContextHolder.set(new LoginPrincipal(9001L, "13888999999", "t1"));
    }
    @AfterEach
    void clear() { LoginContextHolder.clear(); }

    @Test
    void twoLines_throwsItemsInvalid() {
        OrderPreviewCmdExe exe = new OrderPreviewCmdExe(
                productDomainService, productStockHotService, tokenStore, orderProperties);
        OrderPreviewReqVO req = new OrderPreviewReqVO();
        OrderLineReqVO a = new OrderLineReqVO(); a.setProductId(1L); a.setQty(1);
        OrderLineReqVO b = new OrderLineReqVO(); b.setProductId(2L); b.setQty(1);
        req.setItems(List.of(a, b));
        BusinessException ex = assertThrows(BusinessException.class, () -> exe.execute(req));
        assertEquals(OrderErrorCodeEnum.ORDER_ITEMS_INVALID.getCode(), ex.getCode());
    }

    @Test
    void success_savesTokenAndReturnsAmount() {
        Product product = new Product();
        product.setProductId(2085550503315509001L);
        product.setProductName("拿铁");
        product.setSubtitle("x");
        product.setSellPrice(new BigDecimal("18.00"));
        ProductStock stock = new ProductStock();
        stock.setStock(100);
        when(productDomainService.requireOnShelf(2085550503315509001L))
                .thenReturn(new ProductWithStock(product, stock));
        when(productStockHotService.overlayAvail(2085550503315509001L)).thenReturn(Optional.empty());
        OrderPreviewCmdExe exe = new OrderPreviewCmdExe(
                productDomainService, productStockHotService, tokenStore, orderProperties);
        OrderPreviewReqVO req = new OrderPreviewReqVO();
        OrderLineReqVO line = new OrderLineReqVO();
        line.setProductId(2085550503315509001L);
        line.setQty(2);
        req.setItems(List.of(line));
        OrderPreviewResVO res = exe.execute(req);
        assertEquals(new BigDecimal("36.00"), res.getAmount());
        assertNotNull(res.getPlaceToken());
        verify(tokenStore).savePreview(eq(res.getPlaceToken()), any(), eq(Duration.ofMinutes(30)));
        assertEquals(100, res.getItems().get(0).getAvailableStock());
        assertEquals("拿铁", res.getItems().get(0).getProductName());
        assertEquals(new BigDecimal("36.00"), res.getItems().get(0).getLineAmount());
        ArgumentCaptor<OrderPlaceTokenPayload> payloadCap = ArgumentCaptor.forClass(OrderPlaceTokenPayload.class);
        verify(tokenStore).savePreview(eq(res.getPlaceToken()), payloadCap.capture(), eq(Duration.ofMinutes(30)));
        assertEquals(9001L, payloadCap.getValue().memberId());
        assertEquals(2085550503315509001L, payloadCap.getValue().items().get(0).productId());
        assertEquals(2, payloadCap.getValue().items().get(0).qty());
        assertEquals(new BigDecimal("18.00"), payloadCap.getValue().items().get(0).sellPrice());
        verify(productStockHotService, never()).reserve(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void overlayAvail_overridesDbStock() {
        Product product = new Product();
        product.setProductId(2085550503315509001L);
        product.setProductName("拿铁");
        product.setSubtitle("x");
        product.setSellPrice(new BigDecimal("18.00"));
        ProductStock stock = new ProductStock();
        stock.setStock(100);
        when(productDomainService.requireOnShelf(2085550503315509001L))
                .thenReturn(new ProductWithStock(product, stock));
        when(productStockHotService.overlayAvail(2085550503315509001L)).thenReturn(Optional.of(77));
        OrderPreviewCmdExe exe = new OrderPreviewCmdExe(
                productDomainService, productStockHotService, tokenStore, orderProperties);
        OrderPreviewReqVO req = new OrderPreviewReqVO();
        OrderLineReqVO line = new OrderLineReqVO();
        line.setProductId(2085550503315509001L);
        line.setQty(2);
        req.setItems(List.of(line));
        OrderPreviewResVO res = exe.execute(req);
        assertEquals(77, res.getItems().get(0).getAvailableStock());
    }

    @Test
    void insufficientStock_throwsStockInsufficient() {
        Product product = new Product();
        product.setProductId(2085550503315509001L);
        product.setProductName("拿铁");
        product.setSellPrice(new BigDecimal("18.00"));
        ProductStock stock = new ProductStock();
        stock.setStock(1);
        when(productDomainService.requireOnShelf(2085550503315509001L))
                .thenReturn(new ProductWithStock(product, stock));
        when(productStockHotService.overlayAvail(2085550503315509001L)).thenReturn(Optional.empty());
        OrderPreviewCmdExe exe = new OrderPreviewCmdExe(
                productDomainService, productStockHotService, tokenStore, orderProperties);
        OrderPreviewReqVO req = new OrderPreviewReqVO();
        OrderLineReqVO line = new OrderLineReqVO();
        line.setProductId(2085550503315509001L);
        line.setQty(2);
        req.setItems(List.of(line));
        BusinessException ex = assertThrows(BusinessException.class, () -> exe.execute(req));
        assertEquals(ProductErrorCodeEnum.STOCK_INSUFFICIENT.getCode(), ex.getCode());
        verify(tokenStore, never()).savePreview(any(), any(), any());
    }
}
