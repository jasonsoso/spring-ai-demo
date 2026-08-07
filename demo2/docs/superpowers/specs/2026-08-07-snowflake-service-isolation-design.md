# demo2 Snowflake 服务隔离自动分配设计规范

**日期**: 2026-08-07  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  

---

## 1. 背景与目标

### 1.1 问题

当前 `SnowflakeIdGenerator` 无参构造写死 `(workerId=1, datacenterId=1)`。微服务多实例部署时，多进程共用同一对节点号会在并发下产生重复 ID。

期望语义：

| 字段 | 含义 | 范围 |
|------|------|------|
| `datacenterId` | 服务 | 0~31 |
| `workerId` | 该服务下的机器/进程 | 0~31 |

且 **不靠人工** 为每台机器维护这两个值。

### 1.2 目标

1. 启动时根据 `spring.application.name` **自动、永久** 绑定 `datacenterId`。
2. 启动时通过 Redis **租约** 自动占用唯一 `workerId`，运行期心跳续约，崩溃靠 TTL 回收。
3. Redis 不可用、dc/worker 槽位耗尽时 **启动失败**，不降级到固定 ID。
4. 改造现有 `SnowflakeIdGenerator`，供 `OrderService` / `DelayTaskService` 等继续注入使用。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 模型 | 服务隔离：`datacenterId`=服务，`workerId`=机器 |
| 协调存储 | Redis（项目已有；不用 MySQL 做租约） |
| 容量 | ≤32 服务 × 每服务 ≤32 实例（标准 Snowflake 5+5） |
| Redis 故障 | Fail-fast，应用启动失败 |
| 服务标识 | `spring.application.name` |
| dc 回收 | 不自动回收；下线后映射保留 |
| 实现风格 | Redis 双 Key（永久 dc 映射 + TTL worker 租约），可用 `StringRedisTemplate` |

### 1.4 非目标（本版不做）

- 修改 Snowflake 位分配，或支持 >32 服务/实例
- Leaf / 号段 / 独立发号中心
- datacenterId 自动回收控制台或运维 API
- 时钟回拨补偿
- 生产环境降级到固定 `(datacenterId, workerId)`
- 哈希服务名/IP 取模分配（已否决，存在碰撞）

---

## 2. 架构

### 2.1 逻辑架构

```text
┌──────────────────────────────┐
│  Spring Boot 实例            │
│  spring.application.name     │
│                              │
│  SnowflakeNodeAllocator      │
│    1) 解析服务名             │
│    2) 分配/读取 datacenterId │
│    3) 租约 workerId + 心跳   │
│           │                  │
│           ▼                  │
│  SnowflakeIdGenerator        │
│    nextId() → Hutool Snowflake│
└────────────┬─────────────────┘
             │
             ▼
        Redis
  dc 映射(永久) + worker 租约(TTL)
```

### 2.2 组件（`com.jason.demo.demo2.framework.id`）

| 组件 | 职责 |
|------|------|
| `SnowflakeProperties` | `key-prefix`、租约 TTL、续约间隔等 |
| `SnowflakeNodeAllocator` | 分配 `(datacenterId, workerId)`；心跳续约；关闭时尽量释放 |
| `SnowflakeIdGenerator` | 持有 Hutool `Snowflake`，提供 `nextId()`；由已分配节点号构造 |
| `SnowflakeIdConfiguration` | `@Bean`：先 allocate，再创建 generator；失败则上下文启动失败 |

单测可直接 `new SnowflakeIdGenerator(workerId, datacenterId)`，不强制连 Redis。

### 2.3 与现有代码关系

- 替换当前写死 `(1, 1)` 的无参生产路径。
- `OrderService`、`DelayTaskService` 等对 `SnowflakeIdGenerator` 的构造注入保持不变。
- 依赖现有 `spring.data.redis.*`；不新增 Redis 集群/密码要求（与 lock4j 设计一致：本地 Docker 即可）。

---

## 3. Redis Key 与算法

### 3.1 Key 设计

前缀由 `app.snowflake.key-prefix` 控制（下文用 `{prefix}` 表示）。

```text
# 服务 → datacenterId（永久，无 TTL）
{prefix}:dc:{applicationName}     →  "7"              # 字符串形式的 0~31
{prefix}:dc:used                  →  Set{0,1,7,…}     # 已占用 datacenterId

# 实例 → workerId（租约）
{prefix}:worker:{dcId}:{workerId} →  "{instanceId}"   # TTL = lease-ttl-seconds
```

- `applicationName`：`spring.application.name`
- `instanceId`：进程启动时生成的 UUID，用于续约/释放时校验持有者

多环境共用一个 Redis 时，必须通过不同的 `key-prefix` 或不同的 `application.name` 隔离，避免 dc/worker 冲突。

### 3.2 分配 datacenterId

1. `GET {prefix}:dc:{applicationName}`，若存在则解析为 long 并返回。
2. 若不存在：在 `0..31` 中找未出现在 `{prefix}:dc:used` 的值，原子完成：
   - `SADD {prefix}:dc:used {id}`
   - `SET {prefix}:dc:{applicationName} {id}`
   - 推荐 Lua 脚本，保证「查空闲 + 占用 + 绑定」原子性；若 `SADD` 时已存在则换下一个 id。
3. 若 32 个槽均已占用 → 抛异常，启动失败。

同一 `applicationName` 多次启动必须得到 **同一个** `datacenterId`。

### 3.3 分配 workerId

1. 生成 `instanceId`。
2. 对 `workerId = 0..31` 依次尝试：
   `SET {prefix}:worker:{dcId}:{workerId} {instanceId} NX EX {lease-ttl-seconds}`
3. 第一个成功的即为本实例 workerId。
4. 全部失败 → 抛异常，启动失败。

### 3.4 心跳与释放

- 调度周期默认 `heartbeat-interval-seconds = lease-ttl-seconds / 3`（默认 TTL=30s → 每 10s）。
- 续约：仅当当前 value 等于本 `instanceId` 时执行 `EXPIRE`（可用 GET+EXPIRE 的 Lua 校验）。
- 若发现 value 已被他人占用：打 error 日志，停止续约；首版不强制 `System.exit`，文档标明此态危险（ID 可能与抢占者冲突的时间窗取决于实现；正常路径下只有 TTL 过期后才会被抢）。
- 优雅停机（`@PreDestroy` / shutdown）：校验 value 后 `DEL` 租约 key。
- 崩溃/kill：依赖 TTL 自动过期，号被回收。

### 3.5 datacenterId 不自动回收

服务下线后 `{prefix}:dc:{name}` 与 `used` 集合中的条目保留，避免新服务复用旧 dc 导致「跨服务 ID 空间」语义混乱。确需回收时由运维手工删除相关 key（本版不提供工具）。

---

## 4. 启动时序与失败

### 4.1 时序

```text
1. Spring 建立 Redis 连接
2. SnowflakeNodeAllocator.allocate()
   ├─ resolve applicationName
   ├─ ensureDatacenterId(applicationName)
   └─ acquireWorkerId(datacenterId)
3. 启动心跳调度
4. 构造 SnowflakeIdGenerator(workerId, datacenterId)
5. 应用对外可用
```

分配必须在依赖 `SnowflakeIdGenerator` 的业务 Bean 使用之前完成；通过 `@Configuration` `@Bean` 依赖顺序保证 fail-fast 发生在上下文刷新阶段。

### 4.2 失败表

| 场景 | 行为 |
|------|------|
| Redis 不可用 / 超时 | 启动失败 |
| datacenter 槽满（>32 不同应用名） | 启动失败 |
| worker 槽满（同服务存活实例 >32） | 启动失败 |
| 续约发现租约被抢 | error 日志 + 停止续约 |

**禁止** 静默回退到 `(1,1)` 或其它固定节点号。

### 4.3 可观测

启动成功必须打 info：

```text
snowflake ready, app={}, datacenterId={}, workerId={}, instanceId={}
```

续约失败打 error，携带相同字段。

### 4.4 时钟

本设计不处理 NTP/时钟回拨；实现与文档仅提醒 Snowflake 对回拨敏感。

---

## 5. 配置

```properties
# 建议含应用或环境，避免多环境共 Redis 撞 key
app.snowflake.key-prefix=app:snowflake
app.snowflake.lease-ttl-seconds=30
app.snowflake.heartbeat-interval-seconds=10
```

服务名不再单独配置，统一使用 `spring.application.name`。

---

## 6. 测试

| 类型 | 覆盖点 |
|------|--------|
| 单元 | dc 首次分配；同名再次启动复用同一 dc |
| 单元 | worker `SET NX` 成功；32 槽占满失败 |
| 单元 | 续约仅当 value 为自己；被抢时续约失败 |
| 单元 | 优雅释放：校验 instanceId 后删除 |
| 集成 | 两实例并行申请得到不同 workerId；TTL 过期后可再申请（Testcontainers Redis 或项目既有 Redis 测试惯例） |
| 回归 | `SnowflakeIdGenerator.nextId()`；`OrderService` / `DelayTaskService` 注入与现有单测 |

---

## 7. 实现要点（给计划用）

1. 去掉生产路径对无参 `(1,1)` 的依赖；保留双参构造供测试。
2. Lua（或等价原子操作）实现 dc 分配与「校验持有者后续约/删除」。
3. 心跳用 Spring `TaskScheduler` 或单线程调度即可，随上下文销毁而停止。
4. 不在本版引入新的中间件依赖（继续用 Spring Data Redis）。

---

## 8. 决策记录（脑暴摘要）

| 议题 | 结论 |
|------|------|
| 协调存储 | 先倾向 MySQL，后因 TTL/租约改为 **Redis** |
| 为何要租约 | 非优雅退出无法释放 worker；无过期会槽位耗尽或误复用撞号 |
| 服务/实例上限 | 均 ≤32 |
| Redis 故障 | Fail-fast |
| 方案选型 | Redis 双 Key（否决：仅自动 worker + 手写 dc；否决：纯 Redisson 封装等价方案作为主路径） |
