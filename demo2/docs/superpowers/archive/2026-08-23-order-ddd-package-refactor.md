# 订单模块 DDD 分包重构 · 功能归档

**归档日期**: 2026-08-24  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现，作为业务模块规范样板  

**设计规范**: [2026-08-23-order-ddd-package-refactor-design.md](../specs/2026-08-23-order-ddd-package-refactor-design.md)  
**实施计划**: [2026-08-23-order-ddd-package-refactor.md](../plans/2026-08-23-order-ddd-package-refactor.md)  
**参考代码**: `com.jason.demo.demo2.order`

---

## 1. 做了什么

将扁平 `order` 包重构为 **app（接入）+ service（领域 + 基础设施）**：

- HTTP 全部 **POST + JSON Body**，路径 `/demo/orders/{action}`
- 应用层 **CmdExe** 编排用例；**ReqVO / ResVO** 对外；MapStruct 转换
- 领域对象 **Order extends OrderDO**；规则在 `pay()` / `cancel()` / 工厂方法
- **OrderRepository** 对外只暴露 `Order`；表映射 **OrderDO** + **OrderMapper**
- 取消三条路径：延时主路径、扫描兜底、HTTP 手动 cancel

---

## 2. 包结构（后续模块照抄骨架）

```
com.jason.demo.demo2.{module}
├── app
│   ├── controller
│   ├── executor          # *CmdExe
│   ├── listener / job      # 可选：MQ、延时、定时
│   ├── support             # HTTP/解析等薄工具
│   ├── vo/req / vo/res
│   └── convert             # *VoConvert (MapStruct)
└── service
    ├── common              # 枚举、常量
    ├── core
    │   ├── domain          # 聚合根，extends *DO
    │   ├── *DomainService
    │   └── *DomainException
    └── infrastructure
        ├── dao/entity + dao/mapper
        └── repository + convert/*DoConvert
```

**依赖方向**: `app → service.core → service.infrastructure`（禁止反向）。

---

## 3. 关键约定（会员 / 商品 / 门店通用）

| 维度 | 约定 |
|------|------|
| 表映射类 | `{Entity}DO`，放 `dao.entity` |
| 领域对象 | `{Entity} extends {Entity}DO`，行为在 domain |
| 持久化出口 | `{Entity}Repository`，禁止 Controller/CmdExe 直接用 Mapper |
| 写操作 | CmdExe + `@Transactional`；状态变更用条件 UPDATE（防并发） |
| HTTP | POST + Body；Demo 路径 `/demo/{modules}/{action}` |
| 创建类动作 | 仅创建用 `{Module}Place*` / `place`（如 `MemberRegister*`，按业务定） |
| 异常 | `{Module}DomainException` → app 层转 HTTP（400/404/409） |
| 测试 | `{Module}*CmdExeTest`、Repository 往返、Controller 集成 |

---

## 4. 参考入口类

| 职责 | 类 |
|------|-----|
| HTTP 薄层 | `order.app.controller.OrderController` |
| 用例编排 | `order.app.executor.OrderPlaceCmdExe` |
| 领域服务 | `order.service.core.OrderDomainService` |
| 聚合根 | `order.service.core.domain.Order` |
| 仓储 | `order.service.infrastructure.repository.OrderRepository` |

---

## 5. 后续新模块流程

1. 复制本归档 + 设计 spec 模板，替换 `{module}` 与用例表  
2. 在 Cursor 中 `@` 引用规则 `demo2-business-ddd` / `demo2-new-business-module`  
3. 先列包结构与 CmdExe 清单，再实现；以 `order` 为对照  
4. 完成后：更新 spec 状态、补测试、归档  

**Cursor 规则目录**: `demo2/.cursor/rules/`
