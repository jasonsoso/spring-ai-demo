# demo2 JetCache 两级缓存（Caffeine + Redis）设计规范

**日期**: 2026-08-29  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  

---

## 1. 背景与目标

### 1.1 问题

商品列表、详情每次请求都打 MySQL（主数据 + 库存投影），再 overlay Redis Hash 可售。商品主数据变更少、读多，适合缓存；仓库内尚无 Spring Cache / Caffeine / JetCache。

已有 Redis（`StringRedisTemplate` + `RedissonClient`）承担热库存闸门、分布式锁、会话、雪花租约，**不能**把目录缓存和 `demo2:stock:{productId}` 混用。

### 1.2 目标

1. 在 `com.jason.demo.demo2.framework.cache` 提供可复用的 **JetCache 两级缓存**：注解 + **Caffeine L1** + **Redis L2**。
2. 商品作为第一个接入点：`listOnShelf` / `requireOnShelf` 走缓存；上/下架主动失效。
3. 可售库存仍走现有 Redis Hash overlay，**不进目录缓存**。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 范围 | framework 能力 + 商品接入；会员/门店本版不接 |
| 框架 | JetCache（不是手写两级 Cache，也不是 Redisson `RLocalCachedMap`） |
| L1 | Caffeine |
| L2 | 复用已有 `RedissonClient`（`jetcache-starter-redisson`），不引 Jedis |
| 版本 | 首选 JetCache **2.7.9**；若与 Spring Boot 4.1 / Redisson 4.1 依赖冲突，改用 **2.8.0.RC**，方案不变 |
| 注解位置 | `ProductDomainService`（CmdExe 跨 Bean 调用，AOP 生效） |
| 缓存内容 | `ProductWithStock` / `List<ProductWithStock>`（MySQL 投影） |
| 可售 | CmdExe 每次 `overlayAvail`；Redis miss 时回退缓存/MySQL 里的 `stock` 字段（与今日行为一致，仅可能略陈旧） |
| 已售 `sellStock` | 随缓存，直到上下架失效或 TTL；不写入热库存 Hash |
| 失效 | 上/下架主动清列表 + 该 `productId` 详情；TTL 仅兜底 |
| 多实例 L1 | 本版不做 pub/sub / `syncLocal` |
| 值序列化 | Kryo5（领域对象未实现 `Serializable`） |
| key 转换 | fastjson2 |
| Redis 前缀 | `demo2:cache:`，与 `demo2:stock:` 隔离 |
| 订单 | 不改订单代码；`requireOnShelf` 被预览/下单调用时自然命中同一详情缓存 |

### 1.4 非目标（本版不做）

- 会员、门店等其它模块接入
- 为订单模块单独改代码或加注解
- 多实例 Caffeine 同步（`syncLocal` / broadcast 清 L1）
- 缓存 `availableStock`、`JsonResult`、ResVO、Controller 返回值
- JetCache 自动刷新、缓存穿透保护、布隆过滤器
- Jedis 客户端、Spring Cache `@Cacheable` 双栈
- 运营改商品文案/价格的 HTTP（当前无此接口，因而无对应失效）
- 前端改动

---

## 2. 架构

### 2.1 逻辑架构

```text
CmdExe（list / get）
    │
    ▼
ProductDomainService          ◄── @Cached / @CacheInvalidate
    │  hit: Caffeine L1
    │  miss: Redis L2（Redisson）
    │  both miss: ProductRepository → MySQL
    ▼
ProductWithStock
    │
    ▼
CmdExe overlayAvail（Redis Hash avail）
    │
    ▼
ResVO / 下单校验用的 available
```

```mermaid
flowchart LR
  subgraph App["demo2"]
    EXE["ProductListCmdExe / ProductGetCmdExe"]
    DS["ProductDomainService"]
    JC["JetCache AOP"]
    L1["Caffeine L1"]
    OV["ProductStockHotService.overlayAvail"]
    EXE --> DS
    DS -.-> JC
    JC --> L1
    JC --> L2["Redis L2 demo2:cache:*"]
    JC -->|miss| REPO["ProductRepository"]
    EXE --> OV
  end
  REPO --> MySQL[(MySQL)]
  OV --> HASH["Redis Hash demo2:stock:{id}"]
```

### 2.2 为何缓存在 DomainService

| 层 | 为何不在这里缓存 |
|----|------------------|
| Controller | 只包 `JsonResult`；缓存会把整段响应（含可售）冻住 |
| CmdExe | 负责 VO 转换 + overlay；缓存应发生在 overlay **之前** |
| Repository | 基础设施查询；失效语义（上/下架）在领域方法上更直观 |

`ProductDomainService` 由 CmdExe 注入调用，不是同类自调用，AOP 有效。

订单预览/下单已调用 `requireOnShelf`，会打到同一 `product:detail:` 缓存。这是 B 方案的自然结果，不是给订单做单独改造。下单可售仍优先 overlay；仅 Hash 不存在时回退 `row.getStock().getStock()`。

### 2.3 读路径（列表 / 详情）

1. CmdExe 调 `listOnShelf` 或 `requireOnShelf`。
2. JetCache：L1 → L2 → MySQL，回填两级。
3. CmdExe 转 VO 后 `overlayAvail` 覆盖 `availableStock`。
4. `requireOnShelf` 在未上架时抛 `BusinessException`（不存在 / 已下架）。**异常不缓存**。空列表是合法结果，**可以缓存**。

### 2.4 写路径（上/下架）

`onShelf` / `offShelf` 先改 MySQL status，再由 `@CacheInvalidate` 删除：

- 整个 `product:list:`（无参列表只有一把 key）
- `product:detail:` + 该 `productId`

`adjustStock` **不再**单独失效：接口要求已下架，下架时已清缓存；再次上架走 `onShelf` 再清一次。

### 2.5 与热库存的边界

| Redis key | 用途 | 本版 |
|-----------|------|------|
| `demo2:stock:{productId}` | 闸门 Hash：仅 `avail` + `seq` | 不改 |
| `demo2:cache:product:list:` / `demo2:cache:product:detail:{id}` | 商品目录投影 | 新增 |

禁止把目录 value 写入热库存 Hash，也禁止用库存 Hash 当商品详情缓存。

---

## 3. 组件与配置

### 3.1 包与类

对齐 `framework.id.configuration` / `framework.delay.config`。

| 单元 | 职责 | 依赖 |
|------|------|------|
| `framework.cache.configuration.JetCacheConfiguration` | `@EnableMethodCache(basePackages = "com.jason.demo.demo2")` | JetCache starter |
| `application.properties` 中 `jetcache.*` | L1/L2 类型、TTL 默认、keyPrefix、Redisson bean 名 | 已有 Redis |
| `product.service.infrastructure.cache.ProductCacheNames` | 缓存 name 常量（对齐 `RedisStockKeys`） | 无 |
| `ProductDomainService` | `@Cached` / `@CacheInvalidate` | 上表 name |
| `ProductWithStock` | 增加无参构造，供 Kryo5 | 无新依赖 |

不引入手写 `CacheManager` 包装类；业务只打 JetCache 注解。不需要 `@EnableCreateCacheAnnotation`（本版不用 `@CreateCache`）。

Redisson Spring Bean 名以启动时实际为准（当前 starter 一般为方法名 `redisson`）。配置项：

```text
jetcache.remote.default.redissonClient=redisson
```

若运行期 Bean 名不同，只改这一项，不改方案。

### 3.2 Maven

- `com.alicp.jetcache:jetcache-starter-redisson:2.7.9`
- Caffeine：若 starter 未传递引入，显式加 `com.github.ben-manes.caffeine:caffeine`（版本走 Boot BOM）

### 3.3 `application.properties`（约定）

```properties
jetcache.statIntervalMinutes=0
jetcache.areaInCacheName=false
jetcache.local.default.type=caffeine
jetcache.local.default.limit=1000
jetcache.local.default.keyConvertor=fastjson2
jetcache.local.default.expireAfterWriteInMillis=120000
jetcache.remote.default.type=redisson
jetcache.remote.default.redissonClient=redisson
jetcache.remote.default.keyConvertor=fastjson2
jetcache.remote.default.valueEncoder=kryo5
jetcache.remote.default.valueDecoder=kryo5
jetcache.remote.default.keyPrefix=demo2:cache:
jetcache.remote.default.expireAfterWriteInMillis=600000
```

不启用 `syncLocal`，不配 `broadcastChannel`（本版不做多实例 L1 失效广播）。

方法上的 `expire` / `localExpire` 与上表一致，单位为 **秒**（JetCache 注解约定，不是毫秒）：

| 注解属性 | 值 | 含义 |
|----------|-----|------|
| `cacheType` | `CacheType.BOTH` | L1 + L2 |
| `localExpire` | `120` | Caffeine 2 分钟 |
| `expire` | `600` | Redis 10 分钟 |

### 3.4 缓存 name 与 key

`ProductCacheNames`：

| 常量 | name 字符串 | 方法 key |
|------|-------------|----------|
| `LIST` | `product:list:` | 不写 `key`（无参，整表一份） |
| `DETAIL` | `product:detail:` | `#productId` |

落 Redis 后形态：`demo2:cache:product:list:`、`demo2:cache:product:detail:{productId}`（具体拼接以 JetCache `areaInCacheName=false` + `keyPrefix` 为准；实现后用一次真实 key 核对文档，若多一段分隔符只改常量/前缀，不改两级语义）。

`requireProduct` **不缓存**（上/下架必须读最新 `status`）。

### 3.5 注解清单（`ProductDomainService`）

```text
listOnShelf()
  @Cached(name = LIST, cacheType = BOTH, expire = 600, localExpire = 120)

requireOnShelf(long productId)
  @Cached(name = DETAIL, key = "#productId", cacheType = BOTH, expire = 600, localExpire = 120)

onShelf(long productId) / offShelf(long productId)
  @CacheInvalidate(name = LIST)
  @CacheInvalidate(name = DETAIL, key = "#productId")
```

同一方法两条 `@CacheInvalidate` 使用 JetCache 的 `@Repeatable`（`CacheInvalidateContainer`），不要自写切面。

### 3.6 序列化

- Key：fastjson2。
- Value：Kryo5。`Product` / `ProductStock` 继承 Lombok `@Data` 的 DO，已有无参构造；`ProductWithStock` 目前只有全参构造，**必须补无参构造**。Kryo 按字段编解码，不必改成完整 JavaBean。
- 不缓存 `Optional`；`requireOnShelf` 只缓存成功返回值。

---

## 4. 异常与降级

| 情况 | 行为 |
|------|------|
| L2 Redis 读失败 | 视为 miss，回源 MySQL；HTTP 仍成功。L1 仍可用 |
| L2 写入 / invalidate 失败 | 不阻断主路径；依赖 TTL 与下次成功失效 |
| Kryo 编解码失败 | 当次当 miss，打错误日志（`@Slf4j`，Throwable 放最后一参），不把坏数据当命中 |
| `BusinessException` | 不缓存 |
| 空列表 | 可缓存 |
| 热库存 overlay 失败 | 保持现有逻辑，与目录缓存无关 |
| 同类方法自调用 | `@Cached` 不生效；本版调用链禁止 DomainService 内部调 `listOnShelf` / `requireOnShelf` |

不做穿透保护。demo 商品量小。

---

## 5. 测试

现有 `ProductCmdExeTest` 等 mock `ProductDomainService` 的单测 **保持断言**，缓存不在该链上。

新增：

1. **`ProductWithStockKryoTest`（必测，无 Redis）**  
   用 JetCache Kryo5 编解码器对含 `BigDecimal` / `LocalDateTime` 的 `ProductWithStock` 往返，断言字段一致。
2. **`ProductDomainServiceCacheTest`（Spring 代理 + 真实 L1/L2）**  
   注解写死 `cacheType=BOTH`，测试无法在不配 L2 的情况下假装只有 Caffeine。对 `ProductRepository` mock，启动 JetCache 连开发机已有 Redis（`127.0.0.1:6379`，与 `application.properties` 一致）。连不上则 **skip**（JUnit assume / `@EnabledIf`），不在 CI 无 Redis 时红灯。  
   用例：
   - `listOnShelf` 连调两次 → Repository 只进一次
   - `offShelf` 后再 `listOnShelf` → Repository 再进一次
   - `requireOnShelf(A)` 命中后 `requireOnShelf(B)` 仍打 Repository（按 productId 分 key）
   每例使用独立 cache name 后缀或先 `invalidate`，避免脏 key。

不强制写「Redis 宕机回源」自动化；降级语义按第 4 节实现与代码审阅即可。

---

## 6. HTTP 与前端

无新接口。`POST /demo/products/listProducts`、`getProduct`、`onShelf`、`offShelf` 契约不变。C 端 `member.js` 不改。

验收（手动或现有页面）：

1. 连续两次列表：第二次不应再打商品列表 SQL（日志或测试计数）。
2. 下架后列表立即不再包含该商品；再上架立即出现。
3. 详情可售仍随下单/预占变化（overlay），不随目录缓存冻死。

---

## 7. 实现时注意

- `@EnableMethodCache` 的 `basePackages` 必须覆盖 `com.jason.demo.demo2.product`；放宽到 `com.jason.demo.demo2` 以便后续模块直接打注解。
- 禁止在 Controller 或返回 `JsonResult` 的方法上加 `@Cached`。
- 禁止 `@Cached` 包住 `overlayAvail` 的结果。
- 生产与缓存单测都用 `BOTH`，不要为测试把注解改成 `LOCAL`（profile 改不了方法上的 `cacheType`）。无 Redis 时跳过第 5 节第 2 项，而不是换一套缓存类型。
