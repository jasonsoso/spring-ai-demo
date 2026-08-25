STATUS: DONE
CHANGED_FILES:
- demo2/pom.xml
- demo2/src/main/resources/application.properties
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginRequired.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginPrincipal.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginContextHolder.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthProperties.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthSession.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthSessionService.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthHttpSupport.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptor.java
- demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthWebMvcConfiguration.java
- demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginContextHolderTest.java
- demo2/src/test/java/com/jason/demo/demo2/framework/auth/AuthSessionServiceTest.java
- demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptorTest.java
- .superpowers/sdd/task-1-report.md
TESTS:
- command: 在 demo2 下执行 `.\mvnw.cmd '-Dtest=LoginContextHolderTest,AuthSessionServiceTest,LoginRequiredInterceptorTest' test`（PowerShell 中为避免逗号被解析为参数分隔符，对 `-Dtest` 参数加引号）
- result: BUILD SUCCESS；共运行 9 个测试，Failures 0、Errors 0、Skipped 0。
- coverage: AuthSessionServiceTest 覆盖 Redis 会话写入、读取/反序列化、缺失会话 401、删除；LoginRequiredInterceptorTest 覆盖受保护方法加载登录态、缺少 Token 返回 401、afterCompletion 清理上下文；LoginContextHolderTest 覆盖设置、读取、清理和无上下文 401。
SELF_REVIEW:
- 编译与指定测试命令成功，IDE 对本任务改动未报告 linter 错误。
- Redis 会话写、读、删均有测试；删除通过 StringRedisTemplate mock 验证 Redis key 删除调用，符合计划指定的 Mockito 测试方式。
- LoginRequiredInterceptor.afterCompletion 无条件调用 LoginContextHolder.clear()，并有独立测试验证。
- framework.auth 未 import member.* 或 order.*；未改动 member、order 或前端文件。
- Token 使用 UUID 随机生成并去除连字符，是不透明随机 Token；未引入 JWT 或 Spring Security。
- TTL 默认值和 application.properties 均为 24h；Redis key 前缀为 demo2:auth:session:。
- TTL 依赖固定使用 com.alibaba:transmittable-thread-local:2.14.5。
- 未创建 git commit。
CONCERNS:
- 无任务范围内 concerns。Maven 编译输出包含仓库既有代码的弃用警告，以及 Mockito 动态加载 agent 的提示，但不影响本次 9 个指定测试通过。
