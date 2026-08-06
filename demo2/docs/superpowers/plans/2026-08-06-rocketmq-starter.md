# RocketMQ 精简 Starter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 落地精简 RocketMQ 自动配置（framework 包）与订单事件 Demo（sync/async/orderly/delay 发送 + 并发/顺序消费），并用 Docker 起本地 NameServer/Broker。

**Architecture:** `rocketmq-client` 5.5.0 Remoting API；`RocketMQConfiguration`（`BeanDefinitionRegistryPostProcessor`）按 `rocketmq.consumers/producers.*` 注册 `DefaultMQPushConsumer` / `DefaultMQProducer`；业务继承 `RocketMessage*Listener` 与 `BaseEventPublisher`；发送经 `TransactionUtils.afterCommitSyncExecute`。

**Tech Stack:** Spring Boot 4.1、Java 21、`rocketmq-client` 5.5.0、Jackson、`spring-tx`、`apache/rocketmq:5.3.2` Docker、JUnit 5 + Mockito + AssertJ

**Spec:** [2026-08-06-rocketmq-starter-design.md](../specs/2026-08-06-rocketmq-starter-design.md)

## Global Constraints

- 模块仅限 `demo2`；不改 `demo` 工程
- 框架包：`com.jason.demo.demo2.framework.rocketmq.*`；Demo：`com.jason.demo.demo2.mq.*`；Controller：`com.jason.demo.demo2.controller.OrderMqController`
- 文档与注释中不出现飞书 / digital-food 等名称，只称「文章」
- 客户端必须是 **`rocketmq-client:5.5.0`**（Remoting），不是 `rocketmq-client-java`
- Docker 仅 NameServer + Broker（**不要** Proxy）；镜像 **`apache/rocketmq:5.3.2`**
- 启动校验行为对齐文章：`enabled=false` / 空 consumers / 缺 listener → warn+skip；必填为空 → 抛异常
- JSON 用 Jackson；不做 Heracles / Trace / 灰度 / RequestContext / 失败补偿 / QPS 限流
- Topic：`DEMO_ORDER_TOPIC`；tag：`CONCURRENT` / `ORDERLY`
- 未起 Docker 时，启用了的 Producer/Consumer 在 `start()` 失败会导致应用起不来（对齐文章）

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/docker/rocketmq/docker-compose.yml` | NameServer + Broker |
| `demo2/docker/rocketmq/broker.conf` | `brokerIP1=127.0.0.1`，宿主客户端可达 |
| `demo2/pom.xml` | `rocketmq-client` + `spring-tx` |
| `demo2/src/main/resources/application.properties` | `rocketmq.*` 配置 |
| `.../framework/rocketmq/util/TransactionUtils.java` | 事务后发送 |
| `.../framework/rocketmq/DelayTimeLevel.java` | 18 档延迟 |
| `.../framework/rocketmq/configuration/RocketMQProperties.java` | 配置绑定 |
| `.../framework/rocketmq/configuration/RocketMQConfiguration.java` | 注册 Producer/Consumer |
| `.../framework/rocketmq/AbstractConcurrentlyRocketListener.java` | 并发基类 |
| `.../framework/rocketmq/AbstractOrderlyRocketListener.java` | 顺序基类 |
| `.../framework/rocketmq/RocketMessageConcurrentlyListener.java` | 泛型并发模板 |
| `.../framework/rocketmq/RocketMessageOrderlyListener.java` | 泛型顺序模板 |
| `.../framework/rocketmq/producer/BaseEventPublisher.java` | 发送基类 |
| `.../mq/OrderEvent.java` | 事件模型 |
| `.../mq/InMemoryOrderEventStore.java` | 内存消费结果 |
| `.../mq/OrderEventPublisher.java` | Demo Publisher |
| `.../mq/OrderConcurrentListener.java` | 并发消费 |
| `.../mq/OrderOrderlyListener.java` | 顺序消费 |
| `.../controller/OrderMqController.java` | HTTP API |
| 对应 `src/test/java/...` | 单测 |

---

### Task 1: Docker + Maven 依赖

**Files:**
- Create: `demo2/docker/rocketmq/docker-compose.yml`
- Create: `demo2/docker/rocketmq/broker.conf`
- Modify: `demo2/pom.xml`

**Interfaces:**
- Produces: 本机 `127.0.0.1:9876` NameServer；Broker 端口 `10909/10911/10912`；classpath 含 `rocketmq-client` 与 `spring-tx`

- [ ] **Step 1: 写 broker.conf**

```properties
brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
brokerIP1 = 127.0.0.1
autoCreateTopicEnable = true
autoCreateSubscriptionGroup = true
```

说明：`brokerIP1=127.0.0.1` 让宿主上的 `rocketmq-client` 能连回映射端口；若仍连不上，再改成宿主机局域网 IP。

- [ ] **Step 2: 写 docker-compose.yml**

```yaml
# RocketMQ（Remoting：NameServer + Broker）
# 启动：docker compose -f demo2/docker/rocketmq/docker-compose.yml up -d
# 停止：docker compose -f demo2/docker/rocketmq/docker-compose.yml down
services:
  demo2-rocketmq-namesrv:
    container_name: demo2-rocketmq-namesrv
    image: apache/rocketmq:5.3.2
    ports:
      - "9876:9876"
    command: sh mqnamesrv
    restart: unless-stopped

  demo2-rocketmq-broker:
    container_name: demo2-rocketmq-broker
    image: apache/rocketmq:5.3.2
    ports:
      - "10909:10909"
      - "10911:10911"
      - "10912:10912"
    environment:
      NAMESRV_ADDR: demo2-rocketmq-namesrv:9876
    volumes:
      - ./broker.conf:/home/rocketmq/rocketmq-5.3.2/conf/broker.conf
    depends_on:
      - demo2-rocketmq-namesrv
    command: sh mqbroker -n demo2-rocketmq-namesrv:9876 -c /home/rocketmq/rocketmq-5.3.2/conf/broker.conf
    restart: unless-stopped
```

若镜像内 conf 路径不同（启动报找不到 conf），用 `docker run --rm apache/rocketmq:5.3.2 ls /home/rocketmq` 查实际路径后改 volume；**不要**为此换成 Proxy 方案。

- [ ] **Step 3: 启动并确认 NameServer 端口**

```bash
docker compose -f demo2/docker/rocketmq/docker-compose.yml up -d
docker ps --filter name=demo2-rocketmq
```

Expected: 两个容器 `Up`；本机 `9876` 在 LISTEN（可用 `Test-NetConnection 127.0.0.1 -Port 9876`）。

- [ ] **Step 4: 加 Maven 依赖**

在 `demo2/pom.xml` 的 `<properties>` 增加：

```xml
<rocketmq-client.version>5.5.0</rocketmq-client.version>
```

在 `<dependencies>` 增加：

```xml
<!-- RocketMQ Remoting 客户端（framework.rocketmq） -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client</artifactId>
    <version>${rocketmq-client.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-tx</artifactId>
</dependency>
```

- [ ] **Step 5: Commit**

```bash
git add demo2/docker/rocketmq demo2/pom.xml
git commit -m "chore(demo2): add RocketMQ Docker Compose and rocketmq-client dependency"
```

---

### Task 2: TransactionUtils + DelayTimeLevel

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/util/TransactionUtils.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/DelayTimeLevel.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/util/TransactionUtilsTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/DelayTimeLevelTest.java`

**Interfaces:**
- Produces:
  - `TransactionUtils.afterCommitSyncExecute(Runnable)`
  - `TransactionUtils.afterCommitAsyncExecute(Executor, Runnable)`
  - `DelayTimeLevel` enum with `getLevel(): int`、`getDesc(): String`；levels 1..18

- [ ] **Step 1: 写失败单测 TransactionUtils**

```java
package com.jason.demo.demo2.framework.rocketmq.util;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionUtilsTest {

    @Test
    void afterCommitSyncExecute_runsImmediately_whenNoTransaction() {
        AtomicBoolean ran = new AtomicBoolean(false);
        TransactionUtils.afterCommitSyncExecute(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void afterCommitSyncExecute_runsAfterCommit_whenSynchronizationActive() {
        AtomicBoolean ran = new AtomicBoolean(false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionUtils.afterCommitSyncExecute(() -> ran.set(true));
            assertThat(ran).isFalse();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());
            assertThat(ran).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd demo2
mvn -q -Dtest=TransactionUtilsTest test
```

Expected: 编译失败（类不存在）或测试失败。

- [ ] **Step 3: 实现 TransactionUtils**

```java
package com.jason.demo.demo2.framework.rocketmq.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

public final class TransactionUtils {

    private TransactionUtils() {
    }

    public static void afterCommitSyncExecute(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runnable.run();
                }
            });
        } else {
            runnable.run();
        }
    }

    public static void afterCommitAsyncExecute(Executor executor, Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(runnable);
                }
            });
        } else {
            executor.execute(runnable);
        }
    }
}
```

- [ ] **Step 4: 实现 DelayTimeLevel + 测试**

```java
package com.jason.demo.demo2.framework.rocketmq;

public enum DelayTimeLevel {
    S_1(1, "1s"),
    S_5(2, "5s"),
    S_10(3, "10s"),
    S_30(4, "30s"),
    M_1(5, "1m"),
    M_2(6, "2m"),
    M_5(7, "5m"),
    M_10(8, "10m"),
    M_15(9, "15m"),
    M_30(10, "30m"),
    H_1(11, "1h"),
    H_2(12, "2h"),
    H_3(13, "3h"),
    H_5(14, "5h"),
    H_6(15, "6h"),
    H_10(16, "10h"),
    H_12(17, "12h"),
    H_24(18, "24h");

    private final int level;
    private final String desc;

    DelayTimeLevel(int level, String desc) {
        this.level = level;
        this.desc = desc;
    }

    public int getLevel() {
        return level;
    }

    public String getDesc() {
        return desc;
    }
}
```

```java
package com.jason.demo.demo2.framework.rocketmq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelayTimeLevelTest {

    @Test
    void levels_coverOneToEighteen() {
        assertThat(DelayTimeLevel.values()).hasSize(18);
        assertThat(DelayTimeLevel.S_1.getLevel()).isEqualTo(1);
        assertThat(DelayTimeLevel.H_24.getLevel()).isEqualTo(18);
        assertThat(DelayTimeLevel.S_5.getDesc()).isEqualTo("5s");
    }
}
```

- [ ] **Step 5: 跑通测试并提交**

```bash
cd demo2
mvn -q -Dtest=TransactionUtilsTest,DelayTimeLevelTest test
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq
git commit -m "feat(demo2): add TransactionUtils and DelayTimeLevel"
```

---

### Task 3: RocketMQProperties

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/configuration/RocketMQProperties.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/configuration/RocketMQPropertiesBindingTest.java`

**Interfaces:**
- Produces: `@ConfigurationProperties(prefix = "rocketmq")` with
  - `Map<String, ConsumerConfig> consumers`
  - `Map<String, ProducerConfig> producers`
  - `ConsumerConfig`: `enabled`(default true), `namesrvAddr`, `topic`, `tags`(default `"*"`), `consumerGroup`, `consumeQps`, `listenerBeanName`, `Map<String, Object> props`
  - `ProducerConfig`: `enabled`(default true), `beanName`, `namesrvAddr`, `producerGroup`, `topic`, `tag`, `Map<String, Object> props`

- [ ] **Step 1: 写绑定测试**

```java
package com.jason.demo.demo2.framework.rocketmq.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMQPropertiesBindingTest {

    @Test
    void bindsConsumersAndProducers() {
        Map<String, Object> map = Map.ofEntries(
                Map.entry("rocketmq.consumers.orderConcurrent.enabled", "true"),
                Map.entry("rocketmq.consumers.orderConcurrent.namesrvAddr", "127.0.0.1:9876"),
                Map.entry("rocketmq.consumers.orderConcurrent.topic", "DEMO_ORDER_TOPIC"),
                Map.entry("rocketmq.consumers.orderConcurrent.tags", "CONCURRENT"),
                Map.entry("rocketmq.consumers.orderConcurrent.consumerGroup", "demo-order-concurrent-group"),
                Map.entry("rocketmq.consumers.orderConcurrent.listenerBeanName", "orderConcurrentListener"),
                Map.entry("rocketmq.producers.orderProducer.enabled", "true"),
                Map.entry("rocketmq.producers.orderProducer.namesrvAddr", "127.0.0.1:9876"),
                Map.entry("rocketmq.producers.orderProducer.producerGroup", "demo-order-producer-group"),
                Map.entry("rocketmq.producers.orderProducer.topic", "DEMO_ORDER_TOPIC"),
                Map.entry("rocketmq.producers.orderProducer.tag", "CONCURRENT")
        );
        RocketMQProperties props = Binder.get(new MapConfigurationPropertySource(map))
                .bind("rocketmq", RocketMQProperties.class)
                .get();
        assertThat(props.getConsumers()).containsKey("orderConcurrent");
        assertThat(props.getConsumers().get("orderConcurrent").getListenerBeanName())
                .isEqualTo("orderConcurrentListener");
        assertThat(props.getProducers().get("orderProducer").getTopic())
                .isEqualTo("DEMO_ORDER_TOPIC");
    }
}
```

- [ ] **Step 2: 实现 RocketMQProperties**

```java
package com.jason.demo.demo2.framework.rocketmq.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = RocketMQProperties.PREFIX)
public class RocketMQProperties {

    public static final String PREFIX = "rocketmq";

    private Map<String, ConsumerConfig> consumers = new HashMap<>();
    private Map<String, ProducerConfig> producers = new HashMap<>();

    public Map<String, ConsumerConfig> getConsumers() {
        return consumers;
    }

    public void setConsumers(Map<String, ConsumerConfig> consumers) {
        this.consumers = consumers;
    }

    public Map<String, ProducerConfig> getProducers() {
        return producers;
    }

    public void setProducers(Map<String, ProducerConfig> producers) {
        this.producers = producers;
    }

    public static class ConsumerConfig {
        private boolean enabled = true;
        private String namesrvAddr;
        private String topic;
        private String tags = "*";
        private String consumerGroup;
        private Long consumeQps;
        private String listenerBeanName;
        private Map<String, Object> props = new HashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getNamesrvAddr() { return namesrvAddr; }
        public void setNamesrvAddr(String namesrvAddr) { this.namesrvAddr = namesrvAddr; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getTags() { return tags; }
        public void setTags(String tags) { this.tags = tags; }
        public String getConsumerGroup() { return consumerGroup; }
        public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
        public Long getConsumeQps() { return consumeQps; }
        public void setConsumeQps(Long consumeQps) { this.consumeQps = consumeQps; }
        public String getListenerBeanName() { return listenerBeanName; }
        public void setListenerBeanName(String listenerBeanName) { this.listenerBeanName = listenerBeanName; }
        public Map<String, Object> getProps() { return props; }
        public void setProps(Map<String, Object> props) { this.props = props; }
    }

    public static class ProducerConfig {
        private boolean enabled = true;
        private String beanName;
        private String namesrvAddr;
        private String producerGroup;
        private String topic;
        private String tag;
        private Map<String, Object> props = new HashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBeanName() { return beanName; }
        public void setBeanName(String beanName) { this.beanName = beanName; }
        public String getNamesrvAddr() { return namesrvAddr; }
        public void setNamesrvAddr(String namesrvAddr) { this.namesrvAddr = namesrvAddr; }
        public String getProducerGroup() { return producerGroup; }
        public void setProducerGroup(String producerGroup) { this.producerGroup = producerGroup; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
        public Map<String, Object> getProps() { return props; }
        public void setProps(Map<String, Object> props) { this.props = props; }
    }
}
```

- [ ] **Step 3: 测试通过并提交**

```bash
cd demo2
mvn -q -Dtest=RocketMQPropertiesBindingTest test
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/configuration/RocketMQProperties.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/configuration/RocketMQPropertiesBindingTest.java
git commit -m "feat(demo2): add RocketMQProperties configuration binding"
```

---

### Task 4: Listener 继承链

**Files:**
- Create: `.../AbstractConcurrentlyRocketListener.java`
- Create: `.../AbstractOrderlyRocketListener.java`
- Create: `.../RocketMessageConcurrentlyListener.java`
- Create: `.../RocketMessageOrderlyListener.java`
- Create: `.../RocketMessageConcurrentlyListenerTest.java`

**Interfaces:**
- Produces:
  - `AbstractConcurrentlyRocketListener` implements `MessageListenerConcurrently`
  - `AbstractOrderlyRocketListener` implements `MessageListenerOrderly`
  - `RocketMessageConcurrentlyListener<T>#handleMessage(T, String, MessageExt): ConsumeConcurrentlyStatus`
  - `RocketMessageOrderlyListener<T>#handleMessage(T, String, MessageExt): ConsumeOrderlyStatus`
  - 构造注入 `ObjectMapper`；反序列化失败 → 返回 SUCCESS / CONSUME_SUCCESS

- [ ] **Step 1: 写反序列化失败测试**

```java
package com.jason.demo.demo2.framework.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMessageConcurrentlyListenerTest {

    static class Payload {
        public String id;
    }

    static class StubListener extends RocketMessageConcurrentlyListener<Payload> {
        StubListener(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        protected ConsumeConcurrentlyStatus handleMessage(Payload payload, String message, MessageExt messageExt) {
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    @Test
    void invalidJson_returnsConsumeSuccess() {
        StubListener listener = new StubListener(new ObjectMapper());
        MessageExt ext = new MessageExt();
        ext.setBody("not-json".getBytes(StandardCharsets.UTF_8));
        ConsumeConcurrentlyStatus status = listener.consumeMessage(java.util.List.of(ext), null);
        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
    }
}
```

- [ ] **Step 2: 实现四个 Listener 类**

`AbstractConcurrentlyRocketListener`：取 `msgs.get(0)`；`doReceiveMessage` 抽象；`RuntimeException`/`Exception` → `RECONSUME_LATER`；空列表 → `CONSUME_SUCCESS`。

`AbstractOrderlyRocketListener`：对称；异常 → `SUSPEND_CURRENT_QUEUE_A_MOMENT`；成功 → `SUCCESS`。

`RocketMessageConcurrentlyListener<T>`：

```java
package com.jason.demo.demo2.framework.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

public abstract class RocketMessageConcurrentlyListener<T> extends AbstractConcurrentlyRocketListener {

    private static final Logger log = LoggerFactory.getLogger(RocketMessageConcurrentlyListener.class);

    private final ObjectMapper objectMapper;

    protected RocketMessageConcurrentlyListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected ConsumeConcurrentlyStatus doReceiveMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        T payload;
        try {
            payload = objectMapper.readValue(body, objectMapper.constructType(resolveMessageType()));
        } catch (Exception e) {
            log.error("parse messageBody error, body:{}", body, e);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        return handleMessage(payload, body, messageExt);
    }

    protected abstract ConsumeConcurrentlyStatus handleMessage(T payload, String message, MessageExt messageExt);

    private Type resolveMessageType() {
        return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }
}
```

`RocketMessageOrderlyListener<T>`：同样逻辑，返回 `ConsumeOrderlyStatus`。

- [ ] **Step 3: 测试通过并提交**

```bash
cd demo2
mvn -q -Dtest=RocketMessageConcurrentlyListenerTest test
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq \
  demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq
git commit -m "feat(demo2): add RocketMQ concurrent and orderly listener hierarchy"
```

---

### Task 5: BaseEventPublisher

**Files:**
- Create: `.../framework/rocketmq/producer/BaseEventPublisher.java`
- Create: `.../framework/rocketmq/producer/BaseEventPublisherTest.java`

**Interfaces:**
- Consumes: `TransactionUtils`, `DelayTimeLevel`, `DefaultMQProducer` bean by `producerId`
- Produces:
  - `BaseEventPublisher(String producerId)`
  - `protected void send(Object body, String tag, String... keys)`
  - `protected void sendAsync(Object body, String tag, String... keys)`
  - `protected void sendOrderly(Object body, String tag, String shardingKey, String... keys)`
  - `protected void sendDelay(Object body, String tag, DelayTimeLevel level, String... keys)`
  - 字段：`topic`/`tag` 可由子类 `setTopic`/`setTag` 或 `@PostConstruct` 从配置注入；**Demo 子类显式传 tag**
  - `maxTryTimes` 默认 2；同步发送失败重试；全部失败只打 error

- [ ] **Step 1: 写重试单测（mock DefaultMQProducer）**

```java
package com.jason.demo.demo2.framework.rocketmq.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BaseEventPublisherTest {

    @Mock ApplicationContext applicationContext;
    @Mock DefaultMQProducer producer;

    static class DemoPublisher extends BaseEventPublisher {
        DemoPublisher(String producerId, ObjectMapper objectMapper) {
            super(producerId, objectMapper);
        }

        void publish(String tag) {
            send(java.util.Map.of("orderId", "o1"), tag);
        }
    }

    @Test
    void send_retriesThenSucceeds() throws Exception {
        when(applicationContext.getBean("orderProducer", DefaultMQProducer.class)).thenReturn(producer);
        when(producer.send(any(Message.class)))
                .thenThrow(new RuntimeException("temp"))
                .thenReturn(new SendResult());

        DemoPublisher pub = new DemoPublisher("orderProducer", new ObjectMapper());
        pub.setApplicationContext(applicationContext);
        pub.setTopic("DEMO_ORDER_TOPIC");
        pub.setMaxTryTimes(2);
        pub.initialize();
        pub.publish("CONCURRENT");

        verify(producer, times(2)).send(any(Message.class));
    }
}
```

若 `SendResult` 构造/可见性不便，可 `mock(SendResult.class)`。

- [ ] **Step 2: 实现 BaseEventPublisher**

要点：

- 构造：`(String producerId, ObjectMapper objectMapper)`
- `initialize()`（`@PostConstruct`）：`producer = applicationContext.getBean(producerId, DefaultMQProducer.class)`
- `buildMessage(body, tag, keys)`：Jackson 序列化；`message.setTags(tag)`；keys 空格拼接
- `doSend`：`TransactionUtils.afterCommitSyncExecute(() -> { for 重试 producer.send })`
- `sendAsync`：`producer.send(message, SendCallback)`，同样包在 afterCommit 里
- `sendOrderly`：`producer.send(message, (mqs, msg, arg) -> mqs.get(Math.abs(arg.hashCode()) % mqs.size()), shardingKey)`
- `sendDelay`：`message.setDelayTimeLevel(level.getLevel())` 后走 `doSend`
- 提供 `setApplicationContext` / 实现 `ApplicationContextAware`；`setTopic`/`setMaxTryTimes`

- [ ] **Step 3: 测试通过并提交**

```bash
cd demo2
mvn -q -Dtest=BaseEventPublisherTest test
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer \
  demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/producer
git commit -m "feat(demo2): add BaseEventPublisher with retry and after-commit send"
```

---

### Task 6: RocketMQConfiguration

**Files:**
- Create: `.../configuration/RocketMQConfiguration.java`
- Create: `.../configuration/RocketMQConfigurationValidationTest.java`

**Interfaces:**
- Consumes: `RocketMQProperties`（从 `Environment` `Binder` 绑定）
- Produces: 动态注册
  - Producer Bean 名：`config.beanName` 非空则用之，否则用 map key（如 `orderProducer`）
  - Consumer Bean 名：`{consumerName}_rocketmq_consumer`
  - `initMethod=start`，`destroyMethod=shutdown`
  - Consumer `instanceSupplier` 内：`subscribe` + `applicationContext.getBean(listenerBeanName)` + `registerMessageListener`

- [ ] **Step 1: 写校验测试（不启真实 MQ）**

测试「topic 为空抛 IllegalArgumentException」。可抽出 package-visible 方法 `validateConsumer(String name, ConsumerConfig config)`，或直接测注册逻辑中的校验辅助类。最小做法：在 `RocketMQConfiguration` 增加：

```java
static void requireConsumerFields(String consumerName, RocketMQProperties.ConsumerConfig config) {
    if (config.getConsumerGroup() == null || config.getConsumerGroup().isBlank()) {
        throw new IllegalArgumentException("rocketmq 配置错误, consumerGroup 不能为空: " + consumerName);
    }
    if (config.getNamesrvAddr() == null || config.getNamesrvAddr().isBlank()) {
        throw new IllegalArgumentException("rocketmq 配置错误, namesrvAddr 不能为空: " + consumerName);
    }
    if (config.getTopic() == null || config.getTopic().isBlank()) {
        throw new IllegalArgumentException("rocketmq 配置错误, topic 不能为空: " + consumerName);
    }
}
```

```java
@Test
void requireConsumerFields_rejectsBlankTopic() {
    var cfg = new RocketMQProperties.ConsumerConfig();
    cfg.setConsumerGroup("g");
    cfg.setNamesrvAddr("127.0.0.1:9876");
    cfg.setTopic(" ");
    assertThatThrownBy(() -> RocketMQConfiguration.requireConsumerFields("c1", cfg))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("topic");
}
```

- [ ] **Step 2: 实现 RocketMQConfiguration**

```java
@Configuration
@EnableConfigurationProperties(RocketMQProperties.class)
public class RocketMQConfiguration implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware, EnvironmentAware {
    // fields: Environment environment; ApplicationContext applicationContext;

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        RocketMQProperties props = Binder.get(environment)
                .bind(RocketMQProperties.PREFIX, RocketMQProperties.class)
                .orElseGet(RocketMQProperties::new);
        registerConsumers(registry, props);
        registerProducers(registry, props);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // no-op
    }
}
```

`registerConsumers` 逻辑对齐文章：

1. consumers 空 → `log.warn` return  
2. `!enabled` → warn continue  
3. `requireConsumerFields`  
4. `listenerBeanName` 空白或不在 registry → warn continue  
5. `GenericBeanDefinition` + `instanceSupplier`：

```java
DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(config.getConsumerGroup());
consumer.setNamesrvAddr(config.getNamesrvAddr());
consumer.subscribe(config.getTopic(), config.getTags());
Object listener = applicationContext.getBean(config.getListenerBeanName());
if (listener instanceof MessageListenerOrderly orderly) {
    consumer.registerMessageListener(orderly);
} else if (listener instanceof MessageListenerConcurrently concurrently) {
    consumer.registerMessageListener(concurrently);
} else {
    throw new IllegalStateException("listener must be MessageListenerOrderly or MessageListenerConcurrently: "
            + config.getListenerBeanName());
}
return consumer;
```

`bd.setInitMethodName("start"); bd.setDestroyMethodName("shutdown");`

`registerProducers`：对称；`enabled`；校验 `namesrvAddr`/`producerGroup`；`DefaultMQProducer`；`setInstanceName(producerName)` 避免 clientId 冲突。

注意：`instanceSupplier` 执行时 `applicationContext` 必须已注入（`ApplicationContextAware` 在 BDRPP bean 初始化时设置；supplier 在后续 getBean 时运行）。

- [ ] **Step 3: 测试通过并提交**

```bash
cd demo2
mvn -q -Dtest=RocketMQConfigurationValidationTest test
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/configuration \
  demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/configuration
git commit -m "feat(demo2): register RocketMQ producers and consumers via auto-config"
```

---

### Task 7: 订单 Demo（Store / Publisher / Listeners）

**Files:**
- Create: `.../mq/OrderEvent.java`
- Create: `.../mq/InMemoryOrderEventStore.java`
- Create: `.../mq/OrderEventPublisher.java`
- Create: `.../mq/OrderConcurrentListener.java`
- Create: `.../mq/OrderOrderlyListener.java`
- Create: `.../mq/InMemoryOrderEventStoreTest.java`

**Interfaces:**
- Produces:
  - `record OrderEvent(String orderId, String type, String payload, Instant createdAt)`
  - `InMemoryOrderEventStore.append(String channel, OrderEvent event)`；`list(String orderIdFilter)`；`clear()`
  - `OrderEventPublisher` bean name 无关；内部 `super("orderProducer", objectMapper)`；topic 从 `@Value("${rocketmq.producers.orderProducer.topic}")` 注入
  - Listener bean 名必须为 **`orderConcurrentListener`** / **`orderOrderlyListener`**（与配置 `listenerBeanName` 一致）

- [ ] **Step 1: Store 单测**

```java
@Test
void appendAndFilterByOrderId() {
    InMemoryOrderEventStore store = new InMemoryOrderEventStore();
    store.append("concurrent", new OrderEvent("o1", "CREATED", "a", Instant.parse("2026-08-06T00:00:00Z")));
    store.append("orderly", new OrderEvent("o2", "PAID", "b", Instant.parse("2026-08-06T00:01:00Z")));
    assertThat(store.list("o1")).hasSize(1);
    store.clear();
    assertThat(store.list(null)).isEmpty();
}
```

- [ ] **Step 2: 实现 Demo 类**

`OrderEvent`：record，Jackson 友好（可用紧凑构造；`createdAt` 可空时由 Publisher 填 `Instant.now()`）。

`InMemoryOrderEventStore`：`CopyOnWriteArrayList` 存 `record Stored(String channel, OrderEvent event)`。

`OrderEventPublisher`：

```java
@Component
public class OrderEventPublisher extends BaseEventPublisher {
    public OrderEventPublisher(ObjectMapper objectMapper,
                               @Value("${rocketmq.producers.orderProducer.topic}") String topic) {
        super("orderProducer", objectMapper);
        setTopic(topic);
    }

    public void sendSync(OrderEvent event) { send(event, "CONCURRENT", event.orderId()); }
    public void sendAsync(OrderEvent event) { sendAsync(event, "CONCURRENT", event.orderId()); }
    public void sendOrderly(OrderEvent event) { sendOrderly(event, "ORDERLY", event.orderId(), event.orderId()); }
    public void sendDelay(OrderEvent event, DelayTimeLevel level) { sendDelay(event, "CONCURRENT", level, event.orderId()); }
}
```

Listeners：

```java
@Component("orderConcurrentListener")
public class OrderConcurrentListener extends RocketMessageConcurrentlyListener<OrderEvent> {
    private final InMemoryOrderEventStore store;
    // ctor inject ObjectMapper + store
    @Override
    protected ConsumeConcurrentlyStatus handleMessage(OrderEvent payload, String message, MessageExt messageExt) {
        store.append("concurrent", payload);
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
```

`OrderOrderlyListener`：`@Component("orderOrderlyListener")`，channel=`orderly`。

- [ ] **Step 3: 测试并提交**

```bash
cd demo2
mvn -q -Dtest=InMemoryOrderEventStoreTest test
git add demo2/src/main/java/com/jason/demo/demo2/mq demo2/src/test/java/com/jason/demo/demo2/mq
git commit -m "feat(demo2): add order MQ demo publisher, listeners, and in-memory store"
```

---

### Task 8: Controller + application.properties + 手工验收

**Files:**
- Create: `.../controller/OrderMqController.java`
- Modify: `demo2/src/main/resources/application.properties`
- Create: `.../controller/OrderMqControllerTest.java`（`@WebMvcTest` 或纯 mock Publisher/Store 的切片测试）

**Interfaces:**
- Produces HTTP：
  - `POST /demo/mq/orders/sync|async|orderly`
  - `POST /demo/mq/orders/delay?level=S_5`
  - `GET /demo/mq/orders/events?orderId=`
  - `DELETE /demo/mq/orders/events`
- Body：`{"orderId":"o-1","type":"CREATED","payload":"demo"}`；缺 `createdAt` 时服务端补全

- [ ] **Step 1: 实现 OrderMqController**

```java
@RestController
@RequestMapping("/demo/mq/orders")
public class OrderMqController {
    private final OrderEventPublisher publisher;
    private final InMemoryOrderEventStore store;

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody OrderEventRequest req) {
        OrderEvent event = toEvent(req);
        publisher.sendSync(event);
        return Map.of("ok", true, "mode", "sync", "orderId", event.orderId());
    }
    // async / orderly / delay 同理
    @GetMapping("/events")
    public List<?> events(@RequestParam(required = false) String orderId) {
        return store.list(orderId);
    }
    @DeleteMapping("/events")
    public Map<String, Object> clear() {
        store.clear();
        return Map.of("ok", true);
    }
}
```

`OrderEventRequest` 可放 `mq` 包：`record OrderEventRequest(String orderId, String type, String payload)`。

`delay`：`DelayTimeLevel.valueOf(level)`，非法 level → 400。

- [ ] **Step 2: 追加 application.properties**

```properties
# ===== RocketMQ（framework.rocketmq + 订单 Demo）=====
# Docker: docker compose -f demo2/docker/rocketmq/docker-compose.yml up -d
rocketmq.consumers.orderConcurrent.enabled=true
rocketmq.consumers.orderConcurrent.namesrvAddr=127.0.0.1:9876
rocketmq.consumers.orderConcurrent.topic=DEMO_ORDER_TOPIC
rocketmq.consumers.orderConcurrent.tags=CONCURRENT
rocketmq.consumers.orderConcurrent.consumerGroup=demo-order-concurrent-group
rocketmq.consumers.orderConcurrent.listenerBeanName=orderConcurrentListener

rocketmq.consumers.orderOrderly.enabled=true
rocketmq.consumers.orderOrderly.namesrvAddr=127.0.0.1:9876
rocketmq.consumers.orderOrderly.topic=DEMO_ORDER_TOPIC
rocketmq.consumers.orderOrderly.tags=ORDERLY
rocketmq.consumers.orderOrderly.consumerGroup=demo-order-orderly-group
rocketmq.consumers.orderOrderly.listenerBeanName=orderOrderlyListener

rocketmq.producers.orderProducer.enabled=true
rocketmq.producers.orderProducer.namesrvAddr=127.0.0.1:9876
rocketmq.producers.orderProducer.producerGroup=demo-order-producer-group
rocketmq.producers.orderProducer.topic=DEMO_ORDER_TOPIC
rocketmq.producers.orderProducer.tag=CONCURRENT
```

- [ ] **Step 3: Controller 单测（mock）**

用 `@WebMvcTest(OrderMqController.class)` + `@MockitoBean` Publisher/Store，验证 `POST /sync` 返回 200 且调用 `sendSync`。

- [ ] **Step 4: 全量相关单测**

```bash
cd demo2
mvn -q -Dtest=TransactionUtilsTest,DelayTimeLevelTest,RocketMQPropertiesBindingTest,RocketMessageConcurrentlyListenerTest,BaseEventPublisherTest,RocketMQConfigurationValidationTest,InMemoryOrderEventStoreTest,OrderMqControllerTest test
```

Expected: PASS

- [ ] **Step 5: 手工验收（Docker 已起）**

```bash
# 启动应用后：
curl -s -X POST http://localhost:8080/demo/mq/orders/sync -H "Content-Type: application/json" -d "{\"orderId\":\"o-1\",\"type\":\"CREATED\",\"payload\":\"demo\"}"
curl -s -X POST http://localhost:8080/demo/mq/orders/orderly -H "Content-Type: application/json" -d "{\"orderId\":\"o-1\",\"type\":\"PAID\",\"payload\":\"demo\"}"
curl -s "http://localhost:8080/demo/mq/orders/events?orderId=o-1"
```

Expected: events 中可见 `channel=concurrent` 与 `channel=orderly` 记录（允许短暂延迟，可重试 GET）。

再验 delay / async 各一次。端口以 `application.properties` 中 `server.port` 为准。

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/controller/OrderMqController.java \
  demo2/src/main/java/com/jason/demo/demo2/mq \
  demo2/src/test/java/com/jason/demo/demo2/controller \
  demo2/src/main/resources/application.properties
git commit -m "feat(demo2): expose order RocketMQ demo HTTP APIs"
```

---

## Spec Coverage Checklist

| Spec 项 | Task |
|---------|------|
| Docker NameServer+Broker | Task 1 |
| `rocketmq-client` 5.5.0 | Task 1 |
| `TransactionUtils` | Task 2 |
| `DelayTimeLevel` 18 档 | Task 2 |
| `RocketMQProperties` | Task 3 |
| 并发/顺序 Listener 链 + 反序列化失败成功 | Task 4 |
| `BaseEventPublisher` sync/async/orderly/delay + 重试 + afterCommit | Task 5 |
| `RocketMQConfiguration` 注册与文章校验行为 | Task 6 |
| 订单 Demo + 双 Listener + Store | Task 7 |
| HTTP API + properties + 手工验收 | Task 8 |
| framework / mq 包名分离 | Tasks 2–8 |
| 非目标（Heracles 等）未引入 | 全任务 |

---

## Self-Review Notes

- Producer `topic`/`tag` 统一 Spring 配置；Demo 发送时显式覆盖 tag（`CONCURRENT`/`ORDERLY`）。
- `broker.conf` 路径若与镜像不符，仅改 volume 路径，不改架构。
- `consumeQps` 字段可绑定但不使用（对齐非目标）。
- 不单独拆 starter 模块；`@Configuration` 组件扫描即可，无需 `spring.factories`。
