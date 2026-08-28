package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.app.listener.StockSyncMqListener;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.StockSeqGapException;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSyncMqListenerTest {

    @Mock
    private ProductStockDomainService productStockDomainService;
    @Mock
    private JsonMapper jsonMapper;
    @Mock
    private MessageExt messageExt;

    private AccessibleListener listener;

    @BeforeEach
    void setUp() {
        listener = new AccessibleListener(jsonMapper, productStockDomainService);
    }

    @Test
    void applyDelta_success_consumeSuccess() {
        StockSyncEvent event = sampleEvent();

        ConsumeConcurrentlyStatus status = listener.expose(event, messageExt);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
    }

    @Test
    void seqGap_reconsumeLater() {
        StockSyncEvent event = sampleEvent();
        lenient().when(messageExt.getKeys()).thenReturn("9001 key");
        doThrow(new StockSeqGapException(9001L, 4L, 2L))
                .when(productStockDomainService).applyDelta(event);

        ConsumeConcurrentlyStatus status = listener.expose(event, messageExt);

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, status);
    }

    @Test
    void stockConflict_consumeSuccess() {
        StockSyncEvent event = sampleEvent();
        lenient().when(messageExt.getKeys()).thenReturn("9001 key");
        doThrow(new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT))
                .when(productStockDomainService).applyDelta(event);

        ConsumeConcurrentlyStatus status = listener.expose(event, messageExt);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
    }

    private static StockSyncEvent sampleEvent() {
        return new StockSyncEvent(9001L, 100L, "RESERVE", 2, "100:9001:RESERVE", 4L);
    }

    private static final class AccessibleListener extends StockSyncMqListener {
        private AccessibleListener(JsonMapper jsonMapper, ProductStockDomainService domainService) {
            super(jsonMapper, domainService);
        }

        ConsumeConcurrentlyStatus expose(StockSyncEvent payload, MessageExt messageExt) {
            return handleMessage(payload, "{}", messageExt);
        }
    }
}
