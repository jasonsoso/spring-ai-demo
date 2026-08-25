STATUS: DONE_WITH_CONCERNS

CHANGED_FILES:
- demo2/README.md
- .superpowers/sdd/task-5-report.md

TESTS:
- command: `.\mvnw.cmd -Dtest=LoginContextHolderTest,AuthSessionServiceTest,LoginRequiredInterceptorTest,MemberCmdExeTest,MemberDomainServiceTest,OrderCmdExeTest test`
- result: PowerShell 在未加引号时将逗号解析为参数分隔符，命令在 Maven 启动前以 `ParserError: MissingArgument` 退出；随后使用测试范围完全相同的带引号参数重跑。
- command: `.\mvnw.cmd "-Dtest=LoginContextHolderTest,AuthSessionServiceTest,LoginRequiredInterceptorTest,MemberCmdExeTest,MemberDomainServiceTest,OrderCmdExeTest" test`
- result: PASS，BUILD SUCCESS；共 18 个测试，Failures 0，Errors 0，Skipped 0。
- command: `.\mvnw.cmd "-Dtest=com.jason.demo.demo2.framework.auth.*Test,com.jason.demo.demo2.member.*Test,com.jason.demo.demo2.order.*Test" test`
- result: PASS，BUILD SUCCESS；共 17 个测试，Failures 0，Errors 0，Skipped 0。该 Maven 通配模式覆盖 auth、member 顶层及 order 测试；嵌套包中的 `MemberDomainServiceTest` 已由上一条 focused tests 单独覆盖。
- command: `.\mvnw.cmd -DskipTests compile`
- result: PASS，BUILD SUCCESS；主代码编译成功。

DOCUMENTATION:
- README 原有延时订单 Demo 的 `delay-order-schema.sql` 依赖说明，因此已补充会员 C 端 Demo 的 MySQL 与 Redis 依赖。
- 新环境执行 `delay-order-schema.sql`；已有环境先用该脚本创建 `demo_member`，再执行 `member-module-migration.sql` 为已有 `demo_order` 增加 `member_id`。
- 已记录 Redis 默认 key 前缀 `demo2:auth:session:` 和会话 TTL 配置 `app.auth.session-ttl=24h`。
- 未创建 git commit。

CONCERNS:
- 未执行手工 API 与 Redis 失效 smoke test：当前验证未启动应用，也未准备并确认与应用配置匹配的 MySQL/Redis 运行环境；该测试会产生会员、订单和 Redis 会话数据。
- Maven 输出非阻塞警告：`org.javassist:javassist:3.21.0-GA` effective model 警告，以及 Mockito/Byte Buddy 动态加载 Java agent 的未来兼容性警告。
