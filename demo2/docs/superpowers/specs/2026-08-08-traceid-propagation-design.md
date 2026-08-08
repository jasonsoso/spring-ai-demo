# demo2 TraceId 传播与日志关联设计规范

**日期**: 2026-08-08  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  

---

## 1. 背景与目标

### 1.1 问题

日志 pattern 使用 `%X{traceId}` / `%X{spanId}`，依赖 Micrometer Tracing 写入 MDC。

| 场景 | 现状 |
|------|------|
| HTTP 接口 | 依赖 Boot Observation；若 MDC 未桥接则日志无 `traceId` |
| RocketMQ 消费 | 自建消费线程，未从消息还原上下文，与生产侧断开 |
| 延时任务 Redisson 消费 / FallbackScanner | 自建线程或 `@Scheduled`，无 TraceContext，日志无 `traceId` |

期望：下单 → 发 MQ → 监听能带上同一 `traceId`；延时执行在 MQ 路径可续上，其它入口至少新开根 span，保证日志可读。

### 1.2 目标

1. **HTTP**：普通接口日志必有非空 `traceId`（官方 Observation → MDC，不手写假 UUID）。
2. **MQ 传播**：凡走 `BaseEventPublisher` + 抽象 Listener 的消息，生产注入、消费还原，**同一 `traceId`**（消费为 child span）。
3. **延时执行**：MQ 到期消费可续上下文；Redisson / FallbackScanner 无上下文时 **新开根 span**。
4. **职责分离**：调用方保留「谁触发」业务日志；tracing 由框架层与 `DelayTaskExecutor` 负责。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 总体策略 | 轻量混合：MQ 即时传播同 `traceId`；无上下文入口新开根 |
| 台账落库 | **不**增加 `create_trace_id` 字段 |
| Span 位置 | `DelayTaskExecutor#execute` 集中处理；调用方不各自开 span |
| 实现风格 | `TraceSupport` + `RocketMqTracePropagator`（方案 1） |
| MQ 范围 | 框架层全覆盖（订单、延时任务 MQ 等） |
| Redisson 队列 | 本次不传 trace 头；执行侧新开根兜底 |
| HTTP | 本次一并核对/修复 Observation→MDC |

### 1.4 非目标（本版不做）

- 将「创建」与「数分钟后扫描执行」强行拼成 Tempo 超长单 trace（Scanner/Redisson 用新根即可）
- Redisson `RDelayedQueue` 载荷传播
- 修改 `delay_task` 表结构
- 业务 Listener 各自手写 inject/extract
- 用 `MDC.put` 假 ID 冒充 tracing

---

## 2. 架构

### 2.1 典型链路

```text
[HTTP 下单] trace=T1
    └─ [MQ produce inject] span=S1
           └─ [MQ consume extract + child] span=S2  (trace 仍为 T1)
                  └─ [DelayTaskExecutor] 已有上下文 → child；否则 new root
```

无 MQ 头 / Redisson / Scanner：

```text
[入口无 TraceContext]
    └─ [DelayTaskExecutor] runWithNewSpan → 新根 trace=T2
```

### 2.2 组件

| 组件 | 职责 |
|------|------|
| `TraceSupport` | `runWithNewSpan` / 在已有或给定上下文下开 child；统一 start→scope→run→end，保证 MDC |
| `RocketMqTracePropagator` | 发：当前上下文写入 `Message` user properties；收：从 `MessageExt` extract |
| `BaseEventPublisher` | `buildMessage` 后 inject |
| `AbstractConcurrentlyRocketListener` / `AbstractOrderlyRocketListener` | `consumeMessage` 外包 extract→scope→业务→关闭 |
| `DelayTaskExecutor#execute` | 有当前上下文 → child `delay.task.execute`；否则新根 |
| HTTP / 配置 | 核对 Boot + `spring-boot-starter-opentelemetry` 的 MDC 桥接；必要时修配置，不新建造假 Filter |
| 调用方日志 | `DelayTaskMqListener` / `FallbackScanner` / `RedissonDelayBackend` 保留入口日志 |

建议包位置：

- `com.jason.demo.demo2.framework.trace`（或 `...config` / `...observability`）：`TraceSupport`
- `com.jason.demo.demo2.framework.rocketmq`：`RocketMqTracePropagator`

### 2.3 传播格式

- 优先 **W3C `traceparent`** 写入 RocketMQ user property（与 OTel/Micrometer 默认 propagator 对齐）。
- 可选兼容自有兜底键（仅当 extract 标准头失败时），实现时选定一种并写死，避免多套并存混乱。
- 当前无线程上下文时：**不注入**；消费侧走「无头 → 新开根」路径。

---

## 3. 数据流与错误处理

### 3.1 HTTP

1. 请求进入 → Observation 建 span → MDC 写入 `traceId`/`spanId`。
2. 业务日志与 `TraceIdFilter`（`X-Trace-Id`）读取同一 `Tracer` 上下文。
3. 若日志仍空：排查 sampling、依赖与 MDC 桥接；**禁止**用随机 UUID 写入 MDC 冒充。

### 3.2 发 MQ

1. `BaseEventPublisher.buildMessage` 完成后调用 propagator inject。
2. 所有 `send` / `sendAsync` / `sendOrderly` / `sendDelay` 路径只要走 `buildMessage`，即自动带上上下文。

### 3.3 收 MQ

1. 抽象 Listener 在现有 try/finally 外层（或等价位置）建立 tracing scope，使 `preReceiveMessage` / `doReceiveMessage` / 业务日志均在同一上下文中。
2. 有头且 extract 成功 → child span → 业务。
3. 无头或解析失败 → warn 一次 → 新开根 span 再消费。
4. `finally` 必须关闭 scope/span，避免消费线程复用导致 MDC 泄漏。

### 3.4 DelayTaskExecutor

1. `tracer.currentTraceContext().context() != null` → 开 child。
2. 否则 → `TraceSupport.runWithNewSpan("delay.task.execute", ...)`。
3. 原有锁、CAS、重试、失败标记逻辑不变；tracing 异常不得改变业务控制流。

### 3.5 错误原则

- **传播失败 ≠ 业务失败**：下单、发消息、消费状态（SUCCESS / RECONSUME_LATER 等）保持原语义。
- Tracing 相关异常只打 warn，不影响主路径。

---

## 4. 测试与验收

### 4.1 单测

- `RocketMqTracePropagator`：有当前 span 时 inject 出可解析头；extract 后 `traceId` 一致。
- `TraceSupport`：执行中存在 trace；结束后上下文清理。
- `DelayTaskExecutor`：已有上下文 → child；无上下文 → 新根（mock `Tracer`）。
- Publisher/Listener：断言 Message properties；有头/无头两条路径消费语义不变。

### 4.2 手工验收

1. 普通 HTTP → 日志非空 `traceId`；开关开启时响应头有 `X-Trace-Id`。
2. 下单发 MQ → 生产与消费日志 **同一 `traceId`**。
3. 延时任务走 MQ 到期 → 消费/`execute` 与上游同 trace（或可解释的 child）。
4. 仅 FallbackScanner / Redisson → 日志仍有 `traceId`（可为新根）。
5. 去掉消息头 → 消费不失败，日志仍有 `traceId`（新根）。

### 4.3 回归

- 现有 `DelayTaskExecutorTest`、`FallbackScannerTest` 及订单 MQ 相关测试继续通过。
- 测试环境 `Tracer` 缺失时不 NPE（提供 mock 或 `ObjectProvider` 安全降级，实现计划中选定一种）。

---

## 5. 实现顺序建议

1. `TraceSupport` + 单测  
2. `RocketMqTracePropagator` + Publisher/Listener 接入 + 单测  
3. `DelayTaskExecutor` 包 span  
4. HTTP MDC 核对/修复  
5. 手工验收清单走通  

---

## 6. 开放项（实现期可微调，不改变本规范决策）

- `TraceSupport` 具体类名与包路径以与现有 `config` / `agentscope.observability` 风格一致为准。
- 是否对 async send 使用 `ContextSnapshot` 包装回调线程：若异步回调需要打日志带同一 `traceId`，实现计划中补上；同步 send 路径优先保证。
