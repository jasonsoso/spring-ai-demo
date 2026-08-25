STATUS: DONE
CHANGED_FILES:
- demo2/src/main/resources/db/delay-order-schema.sql
- demo2/src/main/java/com/jason/demo/demo2/member/app/controller/MemberController.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/convert/MemberVoConvert.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberGetProfileCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLoginCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLogoutCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberRegisterCmdExe.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/support/MemberHttpSupport.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/req/DeleteSessionReqVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/req/LoginMemberReqVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/req/RegisterMemberReqVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/DeleteSessionResVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/GetMemberProfileResVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/LoginMemberResVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/LogoutMemberResVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/RegisterMemberResVO.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/common/MemberStatus.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainException.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainService.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/core/PasswordHasher.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/core/domain/Member.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/dao/entity/MemberDO.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/dao/mapper/MemberMapper.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/repository/MemberRepository.java
- demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/repository/convert/MemberDoConvert.java
- demo2/src/test/java/com/jason/demo/demo2/member/MemberCmdExeTest.java
- .superpowers/sdd/task-2-report.md
TESTS:
- command: cd demo2; .\mvnw.cmd -Dtest=MemberCmdExeTest test
- result: BUILD SUCCESS；Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
SELF_REVIEW:
- Controller 已校验 register/login 的 phone、password，以及 deleteSession 的 token；logout/getProfile 均使用 @LoginRequired，logout 从 LoginContextHolder 获取当前 token。
- MemberDomainException 的 NOT_FOUND、CONFLICT、BAD_REQUEST 分别映射为 HTTP 404、409、400。
- MemberVoConvert 与 MemberDoConvert 均使用 MapStruct；Maven 编译及测试通过，生成映射无编译错误。
- 分包及依赖遵循 order 风格：app 调用 service.core，service.core 调用 service.infrastructure；Controller/CmdExe 未直接依赖 Mapper。
- DDL 同时包含自增 id、雪花 member_id、phone、password_hash、avatar_url、status、created_at、updated_at，并为 member_id、phone 建立唯一索引。
- 密码使用 JDK PBKDF2WithHmacSHA256；未引入 Spring Security；登录创建 AuthSession 时传入并保留 avatarUrl。
- 范围核对未修改 order 包或 static 前端，只实现 Task 2 的会员后端、DDL 与 MemberCmdExeTest；未创建 git commit。
CONCERNS:
- 无。

FIXES:
- 修复 Task 2 reviewer Important finding：MemberDomainService.register 在仓储插入边界捕获 DataIntegrityViolationException（包含 DuplicateKeyException），统一转换为 MemberDomainException(Code.CONFLICT, "phone already registered")，并发重复注册不再穿透为 HTTP 500。
- 新增 MemberDomainServiceTest，覆盖“预查未发现会员、insert 发生唯一键冲突”的并发竞态路径。

TESTS:
- RED: cd demo2; .\mvnw.cmd "-Dtest=MemberDomainServiceTest" test
- RED result: BUILD FAILURE；Tests run: 1, Failures: 1；实际抛出 DataIntegrityViolationException，符合修复前预期。
- GREEN: cd demo2; .\mvnw.cmd "-Dtest=MemberCmdExeTest,MemberDomainServiceTest" test
- GREEN result: BUILD SUCCESS；Tests run: 4, Failures: 0, Errors: 0, Skipped: 0。
- LINTS: MemberDomainService.java、MemberDomainServiceTest.java 无诊断错误。

CONCERNS:
- 无。

FIXES:
- 修复 Task 2 复审 finding：MemberDomainService.register 仅捕获 DuplicateKeyException 并转换为 MemberDomainException(Code.CONFLICT)；其他 DataIntegrityViolationException 保持原异常抛出，避免将字段超长、非空约束等错误误报为手机号已注册。
- 更新 MemberDomainServiceTest：唯一键冲突改用 DuplicateKeyException，并新增普通 DataIntegrityViolationException 不被转换且保持同一异常实例的覆盖。

TESTS:
- RED: cd demo2; .\mvnw.cmd "-Dtest=MemberDomainServiceTest" test
- RED result: BUILD FAILURE；Tests run: 2, Failures: 1；普通 DataIntegrityViolationException 被错误转换为 MemberDomainException，符合修复前预期。
- GREEN: cd demo2; .\mvnw.cmd "-Dtest=MemberCmdExeTest,MemberDomainServiceTest" test
- GREEN result: BUILD SUCCESS；Tests run: 5, Failures: 0, Errors: 0, Skipped: 0。
- LINTS: MemberDomainService.java、MemberDomainServiceTest.java 无诊断错误。

CONCERNS:
- 无。
