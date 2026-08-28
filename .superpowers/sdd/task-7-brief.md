### Task 7: Relay + Publisher + Listener

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEventPublisher.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/StockSyncMqListener.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java`
- Modify: `application.properties`（producer/consumer）

**Interfaces:**
- Consumes: `StockSyncEvent`、`applyDelta`、`sendImmediate`
- Produces: Stream → MQ → MySQL；缺口 `RECONSUME_LATER`；发 MQ 失败不 XACK

`application.properties` 追加（namesrv 与现有一致 `127.0.0.1:9876`）：

```properties
rocketmq.producers.stockSyncProducer.enabled=true
rocketmq.producers.stockSyncProducer.namesrvAddr=127.0.0.1:9876
rocketmq.producers.stockSyncProducer.producerGroup=demo-stock-sync-producer-group
rocketmq.producers.stockSyncProducer.topic=DEMO_STOCK_TOPIC

rocketmq.consumers.stockSync.enabled=true
rocketmq.consumers.stockSync.namesrvAddr=127.0.0.1:9876
rocketmq.consumers.stockSync.topic=DEMO_STOCK_TOPIC
rocketmq.consumers.stockSync.tags=*
rocketmq.consumers.stockSync.consumerGroup=demo-stock-sync-group
rocketmq.consumers.stockSync.listenerBeanName=stockSyncMqListener
```

`BaseEventPublisher` 新增（`doSend` 旁，**不要**走 `afterCommit`）：

```java
    protected void sendImmediate(Object messageBodyObj, String... keys) {
        Message message = buildMessage(messageBodyObj, keys);
        Exception last = null;
        for (int i = 0; i < maxTryTimes; i++) {
            try {
                SendResult sendResult = producer.send(message);
                log.info("immediate send success, attempt:{}, result:{}", i + 1, sendResult);
                return;
            } catch (Exception e) {
                last = e;
                log.error("immediate send error, attempt:{}, message:{}", i + 1, messageBodyObj, e);
                if (i < maxTryTimes - 1) {
                    sleepQuietly(100L * (i + 1));
                }
            }
        }
        throw new IllegalStateException("rocketmq immediate send failed after retries", last);
    }
```

`StockSyncEventPublisher`（`com.jason.demo.demo2.product.service.infrastructure.publisher`）：

```java
package com.jason.demo.demo2.product.service.infrastructure.publisher;

@Component
public class StockSyncEventPublisher extends BaseEventPublisher {
    public static final String PRODUCER_ID = "stockSyncProducer";
    public StockSyncEventPublisher() { super(PRODUCER_ID); }
    public void sendNow(StockSyncEvent event) {
        sendImmediate(event, String.valueOf(event.getProductId()), event.getIdempotentKey());
    }
}
```

`StockSyncMqListener`：包名 `com.jason.demo.demo2.product.app.listener`，`@Component("stockSyncMqListener")`，extends `RocketMessageConcurrentlyListener<StockSyncEvent>`。

```java
package com.jason.demo.demo2.product.app.listener;

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
```

`RedisStockOutboxRelay`：包名 `com.jason.demo.demo2.product.app.listener`（Redis Stream `XREADGROUP` 消费者，**不要**放 `app.job`）。`SmartLifecycle` 后台循环（虚拟线程或守护线程）。启动时：

```java
try {
    stringRedisTemplate.opsForStream().createGroup(RedisStockKeys.OUTBOX, ReadOffset.from("0-0"), properties.getOutboxGroup());
} catch (Exception ignored) {
    // BUSYGROUP：组已存在
}
```

循环：`XREADGROUP`（`Consumer.from(group, consumer)`，`StreamReadOptions.empty().count(batch).block(Duration.ofMillis(blockMs))`，`StreamOffset.create(OUTBOX, ReadOffset.lastConsumed())`）。

每条 Record：把 field map 拼成 `StockSyncEvent`（`productId/orderId/qty/seq` 用 `Long.parseLong` / `Integer.parseInt`），`publisher.sendNow(event)` **成功后** `opsForStream().acknowledge(OUTBOX, group, record.getId())`。`sendNow` 抛错 → **不 ACK**。

每 10 次循环调用一次 `claimIdlePending`：`opsForStream().claim(OUTBOX, Consumer.from(group, consumer), Duration.ofSeconds(30), ...idle ids via pending())`；对 claim 到的每条同样 `sendNow` + 成功才 ACK。实现可先 `pending(PendingMessagesSummary)` 再 `claim`；单测覆盖「claim 后 send 失败不 ACK」。

Relay **禁止**调用 `applyDelta` 或任何 Mapper。

`redis-hot-enabled=false` 时 Relay 不启动（`isAutoStartup()` 返回 `properties.isRedisHotEnabled()`）。

- [ ] **Step 1: Write the failing tests**

`StockSyncMqListenerTest`：mock DomainService；`applyDelta` 正常 → `CONSUME_SUCCESS`；抛 `StockSeqGapException` → `RECONSUME_LATER`；抛 `BusinessException(STOCK_CONFLICT)` → `CONSUME_SUCCESS`。

`RedisStockOutboxRelayTest`：抽一个包可见方法 `onRecord(Map<String,String> fields, String recordId)`（或 package-private `dispatch`）：mock publisher `sendNow` 成功 → 调 `acknowledge`；`sendNow` throw → never acknowledge。

- [ ] **Step 2: Run tests to verify they fail**

```powershell
.\mvnw.cmd test "-Dtest=StockSyncMqListenerTest,RedisStockOutboxRelayTest"
```

Expected: FAIL

- [ ] **Step 3: Implement publisher / listener / relay**

按上面完整类实现。Relay 解析 Stream 字段名必须与 Lua XADD 一致：`productId, orderId, optType, qty, idempotentKey, seq`。

- [ ] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=StockSyncMqListenerTest,RedisStockOutboxRelayTest,ProductStockApplyDeltaTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher demo2/src/main/java/com/jason/demo/demo2/product/app/listener demo2/src/main/resources/application.properties demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java
git commit -m "feat(product): relay Redis stock outbox to RocketMQ then project MySQL"
```

---

