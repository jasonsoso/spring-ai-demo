STATUS: DONE
CHANGED_FILES:
- demo2/src/main/resources/db/delay-order-schema.sql
- demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/dao/entity/OrderDO.java
- demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/repository/OrderRepository.java
- demo2/src/main/java/com/jason/demo/demo2/order/service/core/domain/Order.java
- demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainService.java
- demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPlaceCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPaySuccessCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderGetCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderCancelCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderController.java
- demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java
TESTS:
- command: .\mvnw.cmd -Dtest=OrderCmdExeTest test
- result: BUILD SUCCESS；Tests run: 4, Failures: 0, Errors: 0, Skipped: 0。
- command: .\mvnw.cmd -Dtest=OrderCmdExeTest clean test
- result: 全量清理并重新编译后 BUILD SUCCESS；Tests run: 4, Failures: 0, Errors: 0, Skipped: 0。
SELF_REVIEW:
- order 包仅依赖 framework.auth.LoginContextHolder/LoginRequired，未引入 member 模块依赖。
- orderPlace 从登录上下文读取 memberId，Order 创建、DO 映射及 DDL 均保存会员归属。
- pay/get/cancel 将当前 memberId 传入领域服务；查询和条件更新同时约束 orderId、memberId，跨会员访问统一走 order not found/404。
- OrderController 的 orderPlace、pay、get、cancel 均已添加 @LoginRequired。
- expireCancel 仍按 orderId 使用 findById 与无 memberId 的 markCancelled，可在无登录上下文的延时任务中执行。
- OrderCmdExeTest 设置并清理登录上下文，覆盖下单归属以及 pay/get/cancel 的当前会员参数传递。
- 已检索确认 order 源码无 member 模块依赖，未新增 myList，IDE 检查无 linter 错误。
- 未执行 Task 4/5，未创建 git commit。
CONCERNS:
- 无。

FIXES:
- 修复 reviewer Important finding：主 schema 保留 demo_order.member_id 与 idx_demo_order_member 的新库定义，并移除末尾无条件 ALTER TABLE/CREATE INDEX。
- 新增 db/member-module-migration.sql，明确仅供已有表环境手动执行；通过 INFORMATION_SCHEMA + PREPARE 对表、字段和索引进行条件判断，可安全重复执行。

TESTS:
- command: .\mvnw.cmd -Dtest=OrderCmdExeTest test
- result: BUILD SUCCESS；Tests run: 4, Failures: 0, Errors: 0, Skipped: 0。

CONCERNS:
- 无。
