# JetCache 两级缓存（Caffeine + Redis）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 接入 JetCache（Caffeine L1 + Redisson Redis L2），商品列表与详情读走缓存，下单/预览/热库存仍实时查库。

**Architecture:** `framework.cache` 只负责 `@EnableMethodCache` 与 `jetcache.*` 配置。注解打在 `ProductDomainService`：`listOnShelf` / `requireOnShelfWithCache` 为 `@Cached(BOTH)`；`requireOnShelf` 无注解。`ProductGetCmdExe` 只调 `requireOnShelfWithCache`。上/下架 `@CacheInvalidate` 清列表 + 该详情。可售继续 `overlayAvail`，不进目录缓存。

**Tech Stack:** Spring Boot 4.1、Java 21、JetCache `2.8.0.RC`、`jetcache-starter-redisson`、Caffeine、Kryo5 value、jackson3 keyConvertor、JUnit 5 + Mockito + Spring Boot Test

**Spec:** [2026-08-29-jetcache-caffeine-redis-cache-design.md](../specs/2026-08-29-jetcache-caffeine-redis-cache-design.md)

## Global Constraints

- 仅改 `demo2`；不改 `demo` 工程、不改前端 `member.js`
- JetCache **2.8.0.RC**；解析失败才改 **2.7.9** 且 `keyConvertor=jackson`（不是 jackson3）
- **不显式加 fastjson2**；不 exclusion 传递依赖；配置必须 `keyConvertor=jackson3`
- Value：**kryo5**。禁止把 `JacksonJsonCustomizer` 的 `JsonMapper` 交给 JetCache
- L1 TTL **60 秒**，L2 TTL **300 秒**；注解 `localExpire=60`、`expire=300`（单位秒）
- `decodeFilterAllowPatterns` 必须含 `com.jason.demo.demo2.product.`
- Redis key 前缀 `demo2:cache:`，禁止写入 `demo2:stock:{id}`
- 不启用 `syncLocal` / `broadcastChannel`
- `@Cached` 禁止打在 Controller、`JsonResult`、`overlayAvail` 结果、`requireOnShelf`
- 订单 / `ProductStockHotService` **禁止**改调 `requireOnShelfWithCache`
- `ProductGetCmdExe` **必须**调 `requireOnShelfWithCache`
- 不新增 HTTP 接口
- 缓存 AOP 测试无 Redis 时 skip，不要把注解改成 `LOCAL`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/pom.xml` | `jetcache-starter-redisson` 2.8.0.RC + Caffeine |
| `demo2/src/main/resources/application.properties` | `jetcache.*` |
| `demo2/.../framework/cache/configuration/JetCacheConfiguration.java` | `@EnableMethodCache` |
| `demo2/.../product/.../cache/ProductCacheNames.java` | `LIST` / `DETAIL` 常量 |
| `demo2/.../product/.../domain/ProductWithStock.java` | 无参构造 + setter，供 Kryo5 |
| `demo2/.../product/.../ProductDomainService.java` | `@Cached` / `@CacheInvalidate` / `requireOnShelfWithCache` |
| `demo2/.../product/.../ProductGetCmdExe.java` | 改调 `requireOnShelfWithCache` |
| `demo2/src/test/java/.../ProductWithStockKryoTest.java` | Kryo5 往返 |
| `demo2/src/test/java/.../ProductCmdExeTest.java` | mock `requireOnShelfWithCache` |
| `demo2/src/test/java/.../ProductDomainServiceCacheTest.java` | Redis 可达时测 AOP 命中/失效 |

---

### Task 1: Maven、JetCache 配置、EnableMethodCache

**Files:**
- Modify: `demo2/pom.xml`
- Modify: `demo2/src/main/resources/application.properties`（Redis 段之后追加）
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/cache/configuration/JetCacheConfiguration.java`

**Interfaces:**
- Produces: 编译期可引用 `com.alicp.jetcache.anno.*`；启动后方法缓存切面可用；L2 使用名为 `redisson` 的 `RedissonClient` Bean

- [ ] **Step 1: 在 `pom.xml` `<properties>` 增加版本**

在 `<lock4j.version>2.2.7</lock4j.version>` 后插入：

```xml
<jetcache.version>2.8.0.RC</jetcache.version>
```

- [ ] **Step 2: 在 `redisson-spring-boot-starter` 依赖后增加**

```xml
        <dependency>
            <groupId>com.alicp.jetcache</groupId>
            <artifactId>jetcache-starter-redisson</artifactId>
            <version>${jetcache.version}</version>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
```

不要加 `fastjson2`。不要 exclusion JetCache 传递的 fastjson2。

- [ ] **Step 3: 解析依赖**

```bash
mvn -f demo2/pom.xml -q dependency:get -Dartifact=com.alicp.jetcache:jetcache-starter-redisson:2.8.0.RC
```

Expected: BUILD SUCCESS。若构件 404：把 `jetcache.version` 改为 `2.7.9`，后续所有 `keyConvertor=jackson3` 改为 `jackson`，并跳过 `decodeFilter*`（2.7 无此配置）。本计划其余任务按 2.8 书写。

- [ ] **Step 4: 追加 `application.properties`**

紧接 `lock4j.lock-key-prefix=lock4j` 之后：

```properties

# ===== JetCache（Caffeine L1 + Redisson L2）=====
jetcache.statIntervalMinutes=0
jetcache.areaInCacheName=false
jetcache.decodeFilterEnabled=true
jetcache.decodeFilterAllowPatterns[0]=com.jason.demo.demo2.product.
jetcache.local.default.type=caffeine
jetcache.local.default.limit=1000
jetcache.local.default.keyConvertor=jackson3
jetcache.local.default.expireAfterWriteInMillis=60000
jetcache.remote.default.type=redisson
jetcache.remote.default.redissonClient=redisson
jetcache.remote.default.keyConvertor=jackson3
jetcache.remote.default.valueEncoder=kryo5
jetcache.remote.default.valueDecoder=kryo5
jetcache.remote.default.keyPrefix=demo2:cache:
jetcache.remote.default.expireAfterWriteInMillis=300000
```

不配 `syncLocal`、不配 `broadcastChannel`。

- [ ] **Step 5: 创建 `JetCacheConfiguration.java`**

```java
package com.jason.demo.demo2.framework.cache.configuration;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableMethodCache(basePackages = "com.jason.demo.demo2")
public class JetCacheConfiguration {
}
```

不要 `@EnableCreateCacheAnnotation`。不要注入 `JsonMapper`。

- [ ] **Step 6: 编译**

```bash
mvn -f demo2/pom.xml -q -DskipTests compile
```

Expected: BUILD SUCCESS。若启动期（本步不要求跑应用）Redisson Bean 名不是 `redisson`，只改 `jetcache.remote.default.redissonClient`。

- [ ] **Step 7: Commit**

```bash
git add demo2/pom.xml demo2/src/main/resources/application.properties demo2/src/main/java/com/jason/demo/demo2/framework/cache/configuration/JetCacheConfiguration.java
git commit -m "feat(cache): add JetCache Redisson starter and two-level config"
```

---

### Task 2: `ProductWithStock` 可 Kryo 编解码

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductWithStock.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/ProductWithStockKryoTest.java`

**Interfaces:**
- Consumes: `com.alicp.jetcache.support.Kryo5ValueEncoder.INSTANCE` / `Kryo5ValueDecoder.INSTANCE`（`Function`：`apply(Object) -> byte[]`，`apply(byte[]) -> Object`）
- Produces: `ProductWithStock` 无参构造 + `setProduct` / `setStock`；现有 `ProductWithStock(Product, ProductStock)` 保留

- [ ] **Step 1: 写失败测试 `ProductWithStockKryoTest`**

```java
package com.jason.demo.demo2.product;

import com.alicp.jetcache.support.DecodeFilter;
import com.alicp.jetcache.support.Kryo5ValueDecoder;
import com.alicp.jetcache.support.Kryo5ValueEncoder;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProductWithStockKryoTest {

    @BeforeAll
    static void allowProductTypes() {
        DecodeFilter.getDefault().addAllowPatterns("com.jason.demo.demo2.product.");
    }

    @Test
    void kryo5_roundTripsProductWithStockAndList() {
        Product product = new Product();
        product.setProductId(2085550503315509001L);
        product.setProductName("拿铁");
        product.setSellPrice(new BigDecimal("18.00"));
        product.setStatus(ProductStatusEnum.ON_SHELF.name());
        product.setCreatedAt(LocalDateTime.of(2026, 8, 26, 10, 0, 0));
        product.setUpdatedAt(LocalDateTime.of(2026, 8, 26, 10, 0, 0));

        ProductStock stock = new ProductStock();
        stock.setProductId(2085550503315509001L);
        stock.setStock(80);
        stock.setSellStock(12);
        stock.setActualStock(80);
        stock.setWithholdStock(0);
        stock.setUpdatedAt(LocalDateTime.of(2026, 8, 26, 10, 0, 0));

        ProductWithStock row = new ProductWithStock(product, stock);
        byte[] bytes = Kryo5ValueEncoder.INSTANCE.apply(row);
        ProductWithStock decoded = assertInstanceOf(
                ProductWithStock.class, Kryo5ValueDecoder.INSTANCE.apply(bytes));

        assertEquals(2085550503315509001L, decoded.getProduct().getProductId());
        assertEquals("拿铁", decoded.getProduct().getProductName());
        assertEquals(new BigDecimal("18.00"), decoded.getProduct().getSellPrice());
        assertEquals(80, decoded.getStock().getStock());
        assertEquals(12, decoded.getStock().getSellStock());

        ArrayList<ProductWithStock> list = new ArrayList<>(List.of(row));
        byte[] listBytes = Kryo5ValueEncoder.INSTANCE.apply(list);
        @SuppressWarnings("unchecked")
        List<ProductWithStock> decodedList =
                (List<ProductWithStock>) Kryo5ValueDecoder.INSTANCE.apply(listBytes);
        assertEquals(1, decodedList.size());
        assertEquals("拿铁", decodedList.get(0).getProduct().getProductName());
    }
}
```

若 `DecodeFilter` 包名不是 `com.alicp.jetcache.support`，打开 `jetcache-core` 源码改 import，不要关掉过滤器。

- [ ] **Step 2: 跑测试，确认当前失败或编解码抛错**

```bash
mvn -f demo2/pom.xml -Dtest=ProductWithStockKryoTest test
```

Expected: FAIL 或 `DecodeFilterException` / 无法实例化 `ProductWithStock`（无无参构造）。

- [ ] **Step 3: 给 `ProductWithStock` 补无参构造与 setter**

完整文件：

```java
package com.jason.demo.demo2.product.service.core.domain;

public class ProductWithStock {

    private Product product;
    private ProductStock stock;

    public ProductWithStock() {
    }

    public ProductWithStock(Product product, ProductStock stock) {
        this.product = product;
        this.stock = stock;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public ProductStock getStock() {
        return stock;
    }

    public void setStock(ProductStock stock) {
        this.stock = stock;
    }
}
```

- [ ] **Step 4: 再跑 `ProductWithStockKryoTest`**

```bash
mvn -f demo2/pom.xml -Dtest=ProductWithStockKryoTest test
```

Expected: Tests run: 1, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductWithStock.java demo2/src/test/java/com/jason/demo/demo2/product/ProductWithStockKryoTest.java
git commit -m "feat(cache): make ProductWithStock Kryo5-serializable"
```

---

### Task 3: 缓存名、DomainService 注解、详情 CmdExe

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/cache/ProductCacheNames.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductDomainService.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductGetCmdExe.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/product/ProductCmdExeTest.java`（`getProduct_mapsDetail`）

**Interfaces:**
- Consumes: `ProductCacheNames.LIST` = `"product:list:"`；`DETAIL` = `"product:detail:"`
- Produces:
  - `public List<ProductWithStock> listOnShelf()`
  - `public ProductWithStock requireOnShelf(long productId)`（无 `@Cached`）
  - `public ProductWithStock requireOnShelfWithCache(long productId)`（`@Cached`，方法体 `return requireOnShelf(productId);`）
  - `ProductGetCmdExe.execute` 调用 `requireOnShelfWithCache`

- [ ] **Step 1: 先改 `ProductCmdExeTest.getProduct_mapsDetail`，把 mock 换成 `requireOnShelfWithCache`**

将

```java
        when(productDomainService.requireOnShelf(9001L)).thenReturn(row);
```

改为

```java
        when(productDomainService.requireOnShelfWithCache(9001L)).thenReturn(row);
```

- [ ] **Step 2: 编译/跑该测试，确认失败**

```bash
mvn -f demo2/pom.xml -Dtest=ProductCmdExeTest#getProduct_mapsDetail test
```

Expected: 编译失败 `cannot find symbol requireOnShelfWithCache`，或测试失败（GetCmdExe 仍调 `requireOnShelf`，mock 未命中 → NPE）。

- [ ] **Step 3: 创建 `ProductCacheNames.java`**

```java
package com.jason.demo.demo2.product.service.infrastructure.cache;

public final class ProductCacheNames {

    public static final String LIST = "product:list:";
    public static final String DETAIL = "product:detail:";

    private ProductCacheNames() {
    }
}
```

- [ ] **Step 4: 改 `ProductDomainService`（完整类）**

```java
package com.jason.demo.demo2.product.service.core;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import com.jason.demo.demo2.product.service.infrastructure.cache.ProductCacheNames;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductDomainService {

    private final ProductRepository productRepository;

    public ProductDomainService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cached(name = ProductCacheNames.LIST, cacheType = CacheType.BOTH, expire = 300, localExpire = 60)
    public List<ProductWithStock> listOnShelf() {
        return productRepository.listOnShelfWithStock();
    }

    public ProductWithStock requireOnShelf(long productId) {
        ProductWithStock row = productRepository.findOnShelfWithStock(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.PRODUCT_NOT_FOUND));
        if (!ProductStatusEnum.ON_SHELF.name().equals(row.getProduct().getStatus())) {
            throw new BusinessException(ProductErrorCodeEnum.PRODUCT_OFF_SHELF);
        }
        return row;
    }

    @Cached(
            name = ProductCacheNames.DETAIL,
            key = "#productId",
            cacheType = CacheType.BOTH,
            expire = 300,
            localExpire = 60)
    public ProductWithStock requireOnShelfWithCache(long productId) {
        return requireOnShelf(productId);
    }

    public Product requireProduct(long productId) {
        return productRepository.requireByProductId(productId);
    }

    @CacheInvalidate(name = ProductCacheNames.LIST)
    @CacheInvalidate(name = ProductCacheNames.DETAIL, key = "#productId")
    public Product offShelf(long productId) {
        Product product = productRepository.requireByProductId(productId);
        productRepository.updateStatus(productId, ProductStatusEnum.OFF_SHELF);
        product.setStatus(ProductStatusEnum.OFF_SHELF.name());
        return product;
    }

    @CacheInvalidate(name = ProductCacheNames.LIST)
    @CacheInvalidate(name = ProductCacheNames.DETAIL, key = "#productId")
    public Product onShelf(long productId) {
        Product product = productRepository.requireByProductId(productId);
        productRepository.updateStatus(productId, ProductStatusEnum.ON_SHELF);
        product.setStatus(ProductStatusEnum.ON_SHELF.name());
        return product;
    }
}
```

禁止给 `requireOnShelf` / `requireProduct` 加 `@Cached`。`requireOnShelfWithCache` 必须 `return requireOnShelf(productId)`（同类内部调用，加载路径无缓存，外层代理负责缓存）。

- [ ] **Step 5: 改 `ProductGetCmdExe.execute`**

将

```java
        ProductDetailResVO detail = productVoConvert.toDetail(productDomainService.requireOnShelf(productId));
```

改为

```java
        ProductDetailResVO detail = productVoConvert.toDetail(productDomainService.requireOnShelfWithCache(productId));
```

不要动 `ProductListCmdExe`、`OrderPlaceCmdExe`、`OrderPreviewCmdExe`、`ProductStockHotService`。

- [ ] **Step 6: 跑商品 CmdExe 单测**

```bash
mvn -f demo2/pom.xml -Dtest=ProductCmdExeTest,ProductShelfCmdExeTest test
```

Expected: BUILD SUCCESS。`ProductShelfCmdExeTest` 仍 mock `offShelf`/`onShelf`，注解不影响 Mockito。

- [ ] **Step 7: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/cache/ProductCacheNames.java demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductDomainService.java demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductGetCmdExe.java demo2/src/test/java/com/jason/demo/demo2/product/ProductCmdExeTest.java
git commit -m "feat(cache): cache product list and detail reads via JetCache annotations"
```

---

### Task 4: Redis 可达时的 AOP 命中 / 失效测试

**Files:**
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/ProductDomainServiceCacheTest.java`

**Interfaces:**
- Consumes: Task 1 配置 + Task 3 带注解的 `ProductDomainService`；`ProductRepository` 用 `@MockitoBean`
- Produces: Redis 未开则 skip；开了则验证列表/详情缓存与 `requireOnShelf` 不缓存

- [ ] **Step 1: 写 `ProductDomainServiceCacheTest`**

```java
package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.cache.configuration.JetCacheConfiguration;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ProductDomainServiceCacheTest.Slice.class)
class ProductDomainServiceCacheTest {

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @SpringBootApplication(
            scanBasePackages = "com.jason.demo.demo2.framework.cache",
            exclude = DataSourceAutoConfiguration.class)
    @Import({JetCacheConfiguration.class, ProductDomainService.class})
    static class Slice {
    }

    @MockitoBean
    private ProductRepository productRepository;

    @Autowired
    private ProductDomainService productDomainService;

    private static final long PRODUCT_A = 9001L;
    private static final long PRODUCT_B = 9002L;

    @BeforeEach
    void resetRepository() {
        Product product = onShelfProduct(PRODUCT_A);
        when(productRepository.requireByProductId(anyLong())).thenReturn(product);
        when(productRepository.findOnShelfWithStock(PRODUCT_A)).thenReturn(Optional.of(row(PRODUCT_A)));
        when(productRepository.findOnShelfWithStock(PRODUCT_B)).thenReturn(Optional.of(row(PRODUCT_B)));
        when(productRepository.listOnShelfWithStock()).thenReturn(List.of(row(PRODUCT_A)));
        productDomainService.offShelf(PRODUCT_A);
    }

    @Test
    @EnabledIf("redisReachable")
    void listOnShelf_secondCallHitsCache() {
        productDomainService.listOnShelf();
        productDomainService.listOnShelf();
        verify(productRepository, times(1)).listOnShelfWithStock();
    }

    @Test
    @EnabledIf("redisReachable")
    void offShelf_invalidatesListCache() {
        productDomainService.listOnShelf();
        productDomainService.offShelf(PRODUCT_A);
        productDomainService.listOnShelf();
        verify(productRepository, times(2)).listOnShelfWithStock();
    }

    @Test
    @EnabledIf("redisReachable")
    void requireOnShelfWithCache_isPerProductId() {
        productDomainService.requireOnShelfWithCache(PRODUCT_A);
        productDomainService.requireOnShelfWithCache(PRODUCT_A);
        productDomainService.requireOnShelfWithCache(PRODUCT_B);
        verify(productRepository, times(1)).findOnShelfWithStock(PRODUCT_A);
        verify(productRepository, times(1)).findOnShelfWithStock(PRODUCT_B);
    }

    @Test
    @EnabledIf("redisReachable")
    void requireOnShelf_doesNotCache() {
        productDomainService.requireOnShelf(PRODUCT_A);
        productDomainService.requireOnShelf(PRODUCT_A);
        verify(productRepository, times(2)).findOnShelfWithStock(PRODUCT_A);
    }

    @Test
    @EnabledIf("redisReachable")
    void contextLoads_whenRedisUp() {
        verify(productRepository, atLeastOnce()).requireByProductId(anyLong());
    }

    private static ProductWithStock row(long productId) {
        return new ProductWithStock(onShelfProduct(productId), stock(productId));
    }

    private static Product onShelfProduct(long productId) {
        Product product = new Product();
        product.setProductId(productId);
        product.setStatus(ProductStatusEnum.ON_SHELF.name());
        product.setProductName("p-" + productId);
        return product;
    }

    private static ProductStock stock(long productId) {
        ProductStock stock = new ProductStock();
        stock.setProductId(productId);
        stock.setStock(10);
        stock.setSellStock(1);
        stock.setActualStock(10);
        stock.setWithholdStock(0);
        return stock;
    }
}
```

若 Boot 4 没有 `org.springframework.test.context.bean.override.mockito.MockitoBean`，改用项目里其它测试已用的 `@MockBean`（`org.springframework.boot.test.mock.mockito.MockBean`）。

`@BeforeEach` 调 `offShelf` 是为了清 L1+L2 的列表/详情脏 key。`updateStatus` 在 mock 上默认 doNothing。

- [ ] **Step 2: Redis 未启动时跑测试**

```bash
mvn -f demo2/pom.xml -Dtest=ProductDomainServiceCacheTest test
```

Expected: 全部 `@EnabledIf` 用例 **skipped**（或 disabled），BUILD SUCCESS，不要 FAILURE。

- [ ] **Step 3: Redis 已启动时再跑（本机 `127.0.0.1:6379`）**

```bash
docker compose -f demo2/docker/redis/docker-compose.yml up -d
mvn -f demo2/pom.xml -Dtest=ProductDomainServiceCacheTest test
```

Expected: Tests run ≥ 4，Failures: 0。若 Slice 启动失败（缺 `RedissonClient` / JetCache 未自动配置）：在 `Slice` 上 `@Import` `com.alicp.jetcache.autoconfigure.JetCacheAutoConfiguration`，或把 `redissonClient` 改成实际 Bean 名。不要改 `cacheType` 为 `LOCAL`。

- [ ] **Step 4: 回归商品相关单测**

```bash
mvn -f demo2/pom.xml -Dtest=ProductCmdExeTest,ProductShelfCmdExeTest,ProductWithStockKryoTest,ProductDomainServiceCacheTest,OrderPlaceCmdExeTest,OrderPreviewCmdExeTest,ProductStockHotServiceTest test
```

Expected: BUILD SUCCESS。订单测试仍 mock `requireOnShelf`。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/test/java/com/jason/demo/demo2/product/ProductDomainServiceCacheTest.java
git commit -m "test(cache): cover JetCache hits and shelf invalidation when Redis is up"
```

---

## Spec coverage（自检）

| Spec | Task |
|------|------|
| JetCache + Caffeine + Redisson、不引 Jedis/fastjson2 | 1 |
| jackson3 key、kryo5 value、decodeFilter、TTL 60s/300s、`demo2:cache:` | 1 |
| `ProductWithStock` 无参构造 + Kryo 测试 | 2 |
| `listOnShelf` / `requireOnShelfWithCache` `@Cached`；`requireOnShelf` 无缓存 | 3 |
| 上/下架双 `@CacheInvalidate` | 3 |
| `ProductGetCmdExe` 改调用点；订单不改 | 3 |
| AOP 命中/失效/`requireOnShelf` 两次打库；无 Redis skip | 4 |
| 不缓存 VO / overlay / Controller | Global Constraints |
| 前端 / 新 HTTP | 不做 |

无 TBD。`requireOnShelfWithCache` 签名在 Task 3 与 Task 4 一致。
