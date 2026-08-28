package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.app.executor.OrderPlaceCmdExe;
import com.jason.demo.demo2.order.app.vo.req.OrderLineReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPlaceReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenPayload;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenStore;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPlaceCmdExeTest {

    private static final String TOKEN = "place-token-1";
    private static final long PRODUCT_ID = 2085550503315509001L;

    @Mock OrderPlaceTokenStore tokenStore;
    @Mock ProductDomainService productDomainService;
    @Mock ProductStockHotService hotService;
    @Mock OrderStateMachineExecutor executor;
    @Mock DelayTaskService delayTaskService;
    @Mock SnowflakeIdGenerator idGenerator;
    @Mock OrderDomainService orderDomainService;

    private DelayProperties delayProperties;

    @BeforeEach
    void login() {
        delayProperties = new DelayProperties();
        delayProperties.setDefaultDelay(Duration.ofSeconds(30));
        LoginContextHolder.set(new LoginPrincipal(9001L, "13888999999", "t1"));
    }

    @AfterEach
    void clear() {
        LoginContextHolder.clear();
    }

    @Test
    void missingPreview_throwsPlaceTokenInvalid() {
        when(tokenStore.getPreview(TOKEN)).thenReturn(Optional.empty());
        OrderPlaceCmdExe exe = newExe();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> exe.execute(placeReq(new BigDecimal("18.00")), Duration.ofSeconds(10)));

        assertEquals(OrderErrorCodeEnum.PLACE_TOKEN_INVALID.getCode(), ex.getCode());
        verify(executor, never()).fireEvent(any(), any(), any());
        verify(tokenStore, never()).saveResult(any(), any(Long.class), any());
    }

    @Test
    void priceChanged_neverFiresOrSaves() {
        stubPreview(new BigDecimal("18.00"));
        when(tokenStore.tryLock(TOKEN, Duration.ofSeconds(30))).thenReturn(true);
        when(tokenStore.getResult(TOKEN)).thenReturn(Optional.empty());
        Product product = product(new BigDecimal("19.00"));
        ProductStock stock = new ProductStock();
        stock.setStock(100);
        when(productDomainService.requireOnShelf(PRODUCT_ID)).thenReturn(new ProductWithStock(product, stock));
        when(hotService.overlayAvail(PRODUCT_ID)).thenReturn(Optional.empty());
        OrderPlaceCmdExe exe = newExe();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> exe.execute(placeReq(new BigDecimal("18.00")), Duration.ofSeconds(10)));

        assertEquals(OrderErrorCodeEnum.PRICE_CHANGED.getCode(), ex.getCode());
        verify(executor, never()).fireEvent(any(), any(), any());
        verify(tokenStore, never()).saveResult(any(), any(Long.class), any());
        verify(tokenStore).unlock(TOKEN);
    }

    @Test
    void sameTokenSecondTime_returnsExistingWithoutFire() {
        stubPreview(new BigDecimal("18.00"));
        when(tokenStore.tryLock(TOKEN, Duration.ofSeconds(30))).thenReturn(true);
        when(tokenStore.getResult(TOKEN)).thenReturn(Optional.of(55L));
        Order existing = new Order();
        existing.setOrderId(55L);
        existing.setOrderStatus(OrderStatusEnum.SUBMIT.name());
        existing.setPayStatus(PayStatusEnum.WAIT_PAY.name());
        existing.setAmount(new BigDecimal("36.00"));
        when(orderDomainService.requireOrderWithItems(55L, 9001L)).thenReturn(existing);
        OrderPlaceCmdExe exe = newExe();

        OrderPlaceResVO res = exe.execute(placeReq(new BigDecimal("18.00")), Duration.ofSeconds(10));

        assertEquals(55L, res.getOrderId());
        assertEquals(OrderStatusEnum.SUBMIT.name(), res.getOrderStatus());
        assertEquals(PayStatusEnum.WAIT_PAY.name(), res.getPayStatus());
        assertNull(res.getTaskId());
        verify(executor, never()).fireEvent(any(), any(), any());
        verify(hotService, never()).reserve(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(tokenStore).unlock(TOKEN);
    }

    @Test
    void success_schedulesCancelAndSavesResult() {
        stubPreview(new BigDecimal("18.00"));
        when(tokenStore.tryLock(TOKEN, Duration.ofSeconds(30))).thenReturn(true);
        when(tokenStore.getResult(TOKEN)).thenReturn(Optional.empty());
        Product product = product(new BigDecimal("18.00"));
        ProductStock stock = new ProductStock();
        stock.setStock(100);
        when(productDomainService.requireOnShelf(PRODUCT_ID)).thenReturn(new ProductWithStock(product, stock));
        when(hotService.overlayAvail(PRODUCT_ID)).thenReturn(Optional.empty());
        when(idGenerator.nextId()).thenReturn(55L, 66L);
        when(delayTaskService.schedule(eq(DelayTaskType.ORDER_CANCEL), eq("55"), isNull(), eq(Duration.ofSeconds(10))))
                .thenReturn(77L);
        OrderPlaceCmdExe exe = newExe();

        OrderPlaceResVO res = exe.execute(placeReq(new BigDecimal("18.00")), Duration.ofSeconds(10));

        assertEquals(55L, res.getOrderId());
        assertEquals(OrderStatusEnum.SUBMIT.name(), res.getOrderStatus());
        assertEquals(PayStatusEnum.WAIT_PAY.name(), res.getPayStatus());
        assertEquals(new BigDecimal("36.00"), res.getAmount());
        assertEquals(77L, res.getTaskId());
        assertEquals("PT10S", res.getDelay());
        InOrder inOrder = inOrder(executor, tokenStore, delayTaskService);
        inOrder.verify(executor).fireEvent(eq(OrderStatusEnum.INIT), eq(OrderEventEnum.SUBMIT_ORDER), any());
        inOrder.verify(tokenStore).saveResult(TOKEN, 55L, Duration.ofHours(24));
        inOrder.verify(delayTaskService).schedule(DelayTaskType.ORDER_CANCEL, "55", null, Duration.ofSeconds(10));
        verify(tokenStore).unlock(TOKEN);
    }

    private OrderPlaceCmdExe newExe() {
        return new OrderPlaceCmdExe(
                tokenStore,
                productDomainService,
                hotService,
                executor,
                delayTaskService,
                idGenerator,
                delayProperties,
                orderDomainService);
    }

    private void stubPreview(BigDecimal sellPrice) {
        when(tokenStore.getPreview(TOKEN)).thenReturn(Optional.of(new OrderPlaceTokenPayload(
                9001L,
                List.of(new OrderPlaceTokenPayload.Item(PRODUCT_ID, 2, sellPrice)))));
    }

    private static OrderPlaceReqVO placeReq(BigDecimal sellPrice) {
        OrderPlaceReqVO req = new OrderPlaceReqVO();
        req.setPlaceToken(TOKEN);
        OrderLineReqVO line = new OrderLineReqVO();
        line.setProductId(PRODUCT_ID);
        line.setQty(2);
        line.setSellPrice(sellPrice);
        req.setItems(List.of(line));
        return req;
    }

    private static Product product(BigDecimal sellPrice) {
        Product product = new Product();
        product.setProductId(PRODUCT_ID);
        product.setProductName("拿铁");
        product.setSubtitle("x");
        product.setCoverUrl("http://img/latte.png");
        product.setSellPrice(sellPrice);
        product.setMarketPrice(new BigDecimal("20.00"));
        return product;
    }
}
