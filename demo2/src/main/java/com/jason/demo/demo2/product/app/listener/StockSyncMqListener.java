package com.jason.demo.demo2.product.app.listener;

import com.jason.demo.demo2.framework.rocketmq.RocketMessageConcurrentlyListener;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.StockSeqGapException;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 热路径投影消费者。seq 缺口必须重试；CONFIRM/RELEASE 互斥冲突不可重试（否则会打转）。
 */
@Slf4j
@Component("stockSyncMqListener")
public class StockSyncMqListener extends RocketMessageConcurrentlyListener<StockSyncEvent> {

    private final ProductStockDomainService productStockDomainService;

    public StockSyncMqListener(JsonMapper jsonMapper, ProductStockDomainService productStockDomainService) {
        super(jsonMapper);
        this.productStockDomainService = productStockDomainService;
    }

    @Override
    protected ConsumeConcurrentlyStatus handleMessage(StockSyncEvent payload, String message, MessageExt messageExt) {
        try {
            productStockDomainService.applyDelta(payload);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (StockSeqGapException ex) {
            log.warn("stock seq gap, will retry, keys={}", messageExt.getKeys(), ex);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        } catch (BusinessException ex) {
            if (ex.getCode() == ProductErrorCodeEnum.STOCK_CONFLICT.getCode()) {
                log.error("stock conflict on sync, skip retry, keys={}", messageExt.getKeys(), ex);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            throw ex;
        }
    }
}
