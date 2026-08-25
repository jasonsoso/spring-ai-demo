# Member Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a simple member module with phone/password registration and login, Redis-backed opaque token sessions, annotation-based login enforcement, order ownership checks, and a C-side member demo tab.

**Architecture:** Shared authentication lives in `framework.auth` and depends only on Spring MVC, Redis, Jackson, and `TransmittableThreadLocal`; business modules depend on it. The new `member` module follows the existing `order` DDD package style, while `order` stores `memberId` ownership and uses `LoginContextHolder` instead of depending on `member`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, MyBatis-Plus, Redis `StringRedisTemplate`, MapStruct, Lombok, Hutool Snowflake, JDK `PBKDF2WithHmacSHA256`, Alibaba `TransmittableThreadLocal`, vanilla HTML/CSS/JS.

## Global Constraints

- New business code goes under `com.jason.demo.demo2.{module}` and follows `order` module style.
- Business module dependency direction is `app -> service.core -> service.infrastructure`.
- Shared authentication package is `com.jason.demo.demo2.framework.auth`; it must not import `member.*` or `order.*`.
- HTTP endpoints are `POST` JSON endpoints under `/demo/{modules}/{action}` with no path variables.
- Token is a random opaque value, not JWT.
- Redis session deletion must make the next protected request return 401.
- Session TTL is configurable with default `24h`.
- Member data uses both `id` auto-increment primary key and `member_id` Snowflake business ID.
- Do not implement SMS login, real product APIs, or real order list APIs in this plan.
- Do not introduce Spring Security.

---

## File Structure

### Create

- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginRequired.java` — method annotation for protected HTTP handlers.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginPrincipal.java` — immutable login principal.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginContextHolder.java` — `TransmittableThreadLocal` context holder.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthProperties.java` — `app.auth.*` configuration.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthSession.java` — Redis session payload.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthSessionService.java` — token generation, Redis save/read/delete.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptor.java` — annotation-aware MVC interceptor.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthWebMvcConfiguration.java` — registers auth interceptor.
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthHttpSupport.java` — creates 401 exceptions.
- `demo2/src/main/java/com/jason/demo/demo2/member/app/controller/MemberController.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberRegisterCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLoginCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLogoutCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberGetProfileCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/req/RegisterMemberReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/req/LoginMemberReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/req/DeleteSessionReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/RegisterMemberResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/LoginMemberResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/LogoutMemberResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/GetMemberProfileResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/vo/res/DeleteSessionResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/convert/MemberVoConvert.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/support/MemberHttpSupport.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/common/MemberStatus.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/domain/Member.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainService.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainException.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/PasswordHasher.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/dao/entity/MemberDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/dao/mapper/MemberMapper.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/repository/MemberRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/infrastructure/repository/convert/MemberDoConvert.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/auth/AuthSessionServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginContextHolderTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptorTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/member/MemberCmdExeTest.java`
- `demo2/src/main/resources/static/js/tabs/member.js`
- `demo2/src/main/resources/static/css/tabs/member.css`

### Modify

- `demo2/pom.xml` — add Alibaba `transmittable-thread-local` dependency.
- `demo2/src/main/resources/application.properties` — add `app.auth.session-key-prefix` and `app.auth.session-ttl`.
- `demo2/src/main/resources/db/delay-order-schema.sql` — add `demo_member` DDL and `demo_order.member_id` sync SQL.
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/dao/entity/OrderDO.java` — add `memberId`.
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/domain/Order.java` — create/from copy `memberId`.
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainService.java` — add owner-aware methods.
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/repository/OrderRepository.java` — add owner-aware updates.
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPlaceCmdExe.java` — read login principal and create member-owned order.
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPaySuccessCmdExe.java` — pay only current member's order.
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderGetCmdExe.java` — read only current member's order.
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderCancelCmdExe.java` — cancel only current member's order.
- `demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderController.java` — add `@LoginRequired`.
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java` — update expectations for `memberId`.
- `demo2/src/main/resources/static/index.html` — add member tab, CSS link, JS script.
- `demo2/src/main/resources/static/js/tabs/order-delay.js` — include member token in order demo requests if present.

---

## Interfaces Produced Across Tasks

```java
// framework.auth
public record LoginPrincipal(Long memberId, String phone, String token) {}
public final class LoginContextHolder {
    public static void set(LoginPrincipal principal);
    public static LoginPrincipal get();
    public static LoginPrincipal require();
    public static void clear();
}
public class AuthSessionService {
    public AuthSession createSession(Long memberId, String phone, String avatarUrl);
    public AuthSession requireSession(String token);
    public boolean deleteSession(String token);
    public String buildSessionKey(String token);
}

// member
public class MemberRegisterCmdExe {
    public Member execute(String phone, String password, String avatarUrl);
}
public class MemberLoginCmdExe {
    public AuthSession execute(String phone, String password);
}
public class MemberLogoutCmdExe {
    public boolean execute(String token);
}
public class MemberGetProfileCmdExe {
    public Member execute();
}

// order
public OrderPlaceResult execute(BigDecimal amount, Duration delay);
public Order execute(long orderId);
```

---

### Task 1: Shared Authentication Framework

**Files:**
- Modify: `demo2/pom.xml`
- Modify: `demo2/src/main/resources/application.properties`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginRequired.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginPrincipal.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginContextHolder.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthSession.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthSessionService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthHttpSupport.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptor.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/AuthWebMvcConfiguration.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginContextHolderTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/auth/AuthSessionServiceTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptorTest.java`

**Interfaces:**
- Consumes: Spring MVC `HandlerInterceptor`, Redis `StringRedisTemplate`, Jackson `ObjectMapper`.
- Produces: `@LoginRequired`, `LoginContextHolder`, `AuthSessionService`, and `LoginPrincipal`.

- [ ] **Step 1: Add dependency and config**

Add a property in `pom.xml`:

```xml
<transmittable-thread-local.version>2.14.5</transmittable-thread-local.version>
```

Add a dependency near other infrastructure dependencies:

```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>transmittable-thread-local</artifactId>
    <version>${transmittable-thread-local.version}</version>
</dependency>
```

Add to `application.properties` near the Redis section:

```properties
# ===== Auth session（会员登录态）=====
app.auth.session-key-prefix=demo2:auth:session:
app.auth.session-ttl=24h
```

- [ ] **Step 2: Write context holder test**

Create `LoginContextHolderTest`:

```java
package com.jason.demo.demo2.framework.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginContextHolderTest {

    @AfterEach
    void tearDown() {
        LoginContextHolder.clear();
    }

    @Test
    void setGetAndClear() {
        LoginPrincipal principal = new LoginPrincipal(1001L, "13888999999", "token-1");

        LoginContextHolder.set(principal);

        assertEquals(1001L, LoginContextHolder.require().memberId());
        assertEquals("13888999999", LoginContextHolder.require().phone());
        LoginContextHolder.clear();
        assertNull(LoginContextHolder.get());
    }

    @Test
    void requireThrowsWhenMissing() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, LoginContextHolder::require);
        assertEquals(401, ex.getStatusCode().value());
    }
}
```

- [ ] **Step 3: Implement annotation, principal, and context**

Create `LoginRequired.java`:

```java
package com.jason.demo.demo2.framework.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginRequired {
}
```

Create `LoginPrincipal.java`:

```java
package com.jason.demo.demo2.framework.auth;

public record LoginPrincipal(Long memberId, String phone, String token) {
}
```

Create `LoginContextHolder.java`:

```java
package com.jason.demo.demo2.framework.auth;

import com.alibaba.ttl.TransmittableThreadLocal;

public final class LoginContextHolder {

    private static final TransmittableThreadLocal<LoginPrincipal> HOLDER = new TransmittableThreadLocal<>();

    private LoginContextHolder() {
    }

    public static void set(LoginPrincipal principal) {
        HOLDER.set(principal);
    }

    public static LoginPrincipal get() {
        return HOLDER.get();
    }

    public static LoginPrincipal require() {
        LoginPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw AuthHttpSupport.unauthorized("login required");
        }
        return principal;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
```

- [ ] **Step 4: Implement properties and session model**

Create `AuthProperties.java`:

```java
package com.jason.demo.demo2.framework.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String sessionKeyPrefix = "demo2:auth:session:";
    private Duration sessionTtl = Duration.ofHours(24);

    public String getSessionKeyPrefix() {
        return sessionKeyPrefix;
    }

    public void setSessionKeyPrefix(String sessionKeyPrefix) {
        this.sessionKeyPrefix = sessionKeyPrefix;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }
}
```

Create `AuthSession.java`:

```java
package com.jason.demo.demo2.framework.auth;

import java.time.LocalDateTime;

public class AuthSession {

    private String token;
    private Long memberId;
    private String phone;
    private String avatarUrl;
    private LocalDateTime loginAt;
    private long expiresInSeconds;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public LocalDateTime getLoginAt() {
        return loginAt;
    }

    public void setLoginAt(LocalDateTime loginAt) {
        this.loginAt = loginAt;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }
}
```

Create `AuthHttpSupport.java`:

```java
package com.jason.demo.demo2.framework.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuthHttpSupport {

    private AuthHttpSupport() {
    }

    public static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
```

- [ ] **Step 5: Write session service test**

Create `AuthSessionServiceTest` using a Mockito `StringRedisTemplate`:

```java
package com.jason.demo.demo2.framework.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private AuthSessionService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AuthProperties properties = new AuthProperties();
        properties.setSessionKeyPrefix("demo2:auth:session:");
        properties.setSessionTtl(Duration.ofHours(24));
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        service = new AuthSessionService(redis, mapper, properties);
    }

    @Test
    void createSessionStoresRedisValue() {
        AuthSession session = service.createSession(1001L, "13888999999", "https://example.com/a.png");

        assertEquals(1001L, session.getMemberId());
        assertEquals("13888999999", session.getPhone());
        assertEquals("https://example.com/a.png", session.getAvatarUrl());
        assertEquals(86400L, session.getExpiresInSeconds());
        verify(values).set(eq("demo2:auth:session:" + session.getToken()), org.mockito.ArgumentMatchers.anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void requireSessionThrowsWhenMissing() {
        when(values.get("demo2:auth:session:gone")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.requireSession("gone"));

        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void deleteSessionDelegatesToRedis() {
        when(redis.delete("demo2:auth:session:t1")).thenReturn(true);

        assertTrue(service.deleteSession("t1"));
    }
}
```

- [ ] **Step 6: Implement session service**

Create `AuthSessionService.java`:

```java
package com.jason.demo.demo2.framework.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthSessionService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;

    public AuthSessionService(StringRedisTemplate redis, ObjectMapper objectMapper, AuthProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public AuthSession createSession(Long memberId, String phone, String avatarUrl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthSession session = new AuthSession();
        session.setToken(token);
        session.setMemberId(memberId);
        session.setPhone(phone);
        session.setAvatarUrl(avatarUrl);
        session.setLoginAt(LocalDateTime.now());
        session.setExpiresInSeconds(properties.getSessionTtl().toSeconds());
        redis.opsForValue().set(buildSessionKey(token), toJson(session), properties.getSessionTtl());
        return session;
    }

    public AuthSession requireSession(String token) {
        String raw = redis.opsForValue().get(buildSessionKey(token));
        if (raw == null || raw.isBlank()) {
            throw AuthHttpSupport.unauthorized("login expired");
        }
        AuthSession session = fromJson(raw);
        session.setToken(token);
        session.setExpiresInSeconds(properties.getSessionTtl().toSeconds());
        return session;
    }

    public boolean deleteSession(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.delete(buildSessionKey(token)));
    }

    public String buildSessionKey(String token) {
        return properties.getSessionKeyPrefix() + token;
    }

    private String toJson(AuthSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize auth session", e);
        }
    }

    private AuthSession fromJson(String raw) {
        try {
            return objectMapper.readValue(raw, AuthSession.class);
        } catch (JsonProcessingException e) {
            throw AuthHttpSupport.unauthorized("invalid session");
        }
    }
}
```

- [ ] **Step 7: Write interceptor test**

Create `LoginRequiredInterceptorTest`:

```java
package com.jason.demo.demo2.framework.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginRequiredInterceptorTest {

    @AfterEach
    void tearDown() {
        LoginContextHolder.clear();
    }

    @Test
    void protectedMethodLoadsSession() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthSession session = new AuthSession();
        session.setMemberId(1001L);
        session.setPhone("13888999999");
        when(sessions.requireSession("abc")).thenReturn(session);
        LoginRequiredInterceptor interceptor = new LoginRequiredInterceptor(sessions);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer abc");

        boolean result = interceptor.preHandle(request, mock(HttpServletResponse.class), handler("protectedEndpoint"));

        assertTrue(result);
        assertEquals(1001L, LoginContextHolder.require().memberId());
        assertEquals("abc", LoginContextHolder.require().token());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        LoginRequiredInterceptor interceptor = new LoginRequiredInterceptor(mock(AuthSessionService.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> interceptor.preHandle(mock(HttpServletRequest.class), mock(HttpServletResponse.class), handler("protectedEndpoint")));

        assertEquals(401, ex.getStatusCode().value());
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = DemoController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(new DemoController(), method);
    }

    private static class DemoController {
        @LoginRequired
        void protectedEndpoint() {
        }
    }
}
```

- [ ] **Step 8: Implement interceptor and MVC configuration**

Create `LoginRequiredInterceptor.java`:

```java
package com.jason.demo.demo2.framework.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginRequiredInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthSessionService authSessionService;

    public LoginRequiredInterceptor(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod) || !requiresLogin(handlerMethod)) {
            return true;
        }
        String token = resolveToken(request);
        AuthSession session = authSessionService.requireSession(token);
        LoginContextHolder.set(new LoginPrincipal(session.getMemberId(), session.getPhone(), token));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        LoginContextHolder.clear();
    }

    private static boolean requiresLogin(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(LoginRequired.class)
                || handlerMethod.getBeanType().isAnnotationPresent(LoginRequired.class);
    }

    private static String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            throw AuthHttpSupport.unauthorized("missing token");
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw AuthHttpSupport.unauthorized("invalid token");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw AuthHttpSupport.unauthorized("invalid token");
        }
        return token;
    }
}
```

Create `AuthWebMvcConfiguration.java`:

```java
package com.jason.demo.demo2.framework.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthWebMvcConfiguration implements WebMvcConfigurer {

    private final LoginRequiredInterceptor loginRequiredInterceptor;

    public AuthWebMvcConfiguration(LoginRequiredInterceptor loginRequiredInterceptor) {
        this.loginRequiredInterceptor = loginRequiredInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginRequiredInterceptor).addPathPatterns("/demo/**");
    }
}
```

- [ ] **Step 9: Run auth tests**

Run:

```powershell
cd demo2; .\mvnw.cmd -Dtest=LoginContextHolderTest,AuthSessionServiceTest,LoginRequiredInterceptorTest test
```

Expected: all three test classes pass.

- [ ] **Step 10: Commit checkpoint**

Use this message when committing is allowed in the execution session:

```bash
feat(demo2): add shared auth session framework
```

---

### Task 2: Member Backend Module

**Files:**
- Modify: `demo2/src/main/resources/db/delay-order-schema.sql`
- Create all `member/**` files listed in File Structure.
- Test: `demo2/src/test/java/com/jason/demo/demo2/member/MemberCmdExeTest.java`

**Interfaces:**
- Consumes: `SnowflakeIdGenerator`, `AuthSessionService`.
- Produces: `/demo/members/register`, `/demo/members/login`, `/demo/members/logout`, `/demo/members/getProfile`, `/demo/members/deleteSession`.

- [ ] **Step 1: Add member DDL**

Append this SQL to `delay-order-schema.sql` after `delay_task`:

```sql
CREATE TABLE IF NOT EXISTS demo_member (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    member_id      BIGINT       NOT NULL COMMENT '会员业务ID（雪花）',
    phone          VARCHAR(32)  NOT NULL COMMENT '手机号',
    password_hash  VARCHAR(255) NOT NULL COMMENT '密码哈希',
    avatar_url     VARCHAR(512) NULL COMMENT '头像URL',
    status         VARCHAR(32)  NOT NULL COMMENT '会员状态：NORMAL/DISABLED',
    created_at     DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at     DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_member_member_id (member_id),
    UNIQUE KEY uk_demo_member_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示会员表';
```

- [ ] **Step 2: Write member command tests**

Create `MemberCmdExeTest.java`:

```java
package com.jason.demo.demo2.member;

import com.jason.demo.demo2.framework.auth.AuthSession;
import com.jason.demo.demo2.framework.auth.AuthSessionService;
import com.jason.demo.demo2.framework.auth.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.LoginPrincipal;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.member.app.executor.MemberGetProfileCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberLoginCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberRegisterCmdExe;
import com.jason.demo.demo2.member.service.common.MemberStatus;
import com.jason.demo.demo2.member.service.core.MemberDomainService;
import com.jason.demo.demo2.member.service.core.PasswordHasher;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberCmdExeTest {

    @AfterEach
    void tearDown() {
        LoginContextHolder.clear();
    }

    @Test
    void registerCreatesNormalMemberWithSnowflakeId() {
        MemberDomainService domainService = mock(MemberDomainService.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(idGenerator.nextId()).thenReturn(9001L);
        when(passwordHasher.hash("pwd123456")).thenReturn("hashed");
        MemberRegisterCmdExe exe = new MemberRegisterCmdExe(domainService, idGenerator, passwordHasher);

        Member member = exe.execute("13888999999", "pwd123456", "https://example.com/a.png");

        assertEquals(9001L, member.getMemberId());
        assertEquals("13888999999", member.getPhone());
        assertEquals("https://example.com/a.png", member.getAvatarUrl());
        assertEquals(MemberStatus.NORMAL.name(), member.getStatus());
        verify(domainService).register(member);
    }

    @Test
    void loginCreatesAuthSession() {
        MemberDomainService domainService = mock(MemberDomainService.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        AuthSessionService authSessionService = mock(AuthSessionService.class);
        Member member = member();
        AuthSession session = new AuthSession();
        session.setToken("t1");
        session.setMemberId(9001L);
        session.setPhone("13888999999");
        when(domainService.requireLoginMember("13888999999")).thenReturn(member);
        when(passwordHasher.matches("pwd123456", "hashed")).thenReturn(true);
        when(authSessionService.createSession(9001L, "13888999999", "https://example.com/a.png")).thenReturn(session);
        MemberLoginCmdExe exe = new MemberLoginCmdExe(domainService, passwordHasher, authSessionService);

        AuthSession result = exe.execute("13888999999", "pwd123456");

        assertEquals("t1", result.getToken());
    }

    @Test
    void profileUsesLoginContext() {
        MemberDomainService domainService = mock(MemberDomainService.class);
        LoginContextHolder.set(new LoginPrincipal(9001L, "13888999999", "t1"));
        when(domainService.requireByMemberId(9001L)).thenReturn(member());
        MemberGetProfileCmdExe exe = new MemberGetProfileCmdExe(domainService);

        Member result = exe.execute();

        assertEquals(9001L, result.getMemberId());
    }

    private static Member member() {
        Member member = new Member();
        member.setMemberId(9001L);
        member.setPhone("13888999999");
        member.setPasswordHash("hashed");
        member.setAvatarUrl("https://example.com/a.png");
        member.setStatus(MemberStatus.NORMAL.name());
        return member;
    }
}
```

- [ ] **Step 3: Implement member domain and hashing**

Create `MemberStatus.java`:

```java
package com.jason.demo.demo2.member.service.common;

public enum MemberStatus {
    NORMAL,
    DISABLED
}
```

Create `MemberDO.java` with Lombok and MyBatis-Plus:

```java
package com.jason.demo.demo2.member.service.infrastructure.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("demo_member")
public class MemberDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private String phone;
    private String passwordHash;
    private String avatarUrl;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

Create `Member.java`:

```java
package com.jason.demo.demo2.member.service.core.domain;

import com.jason.demo.demo2.member.service.common.MemberStatus;
import com.jason.demo.demo2.member.service.core.MemberDomainException;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;

import java.time.LocalDateTime;

public class Member extends MemberDO {

    public static Member create(long memberId, String phone, String passwordHash, String avatarUrl, LocalDateTime now) {
        if (phone == null || phone.isBlank()) {
            throw new MemberDomainException(MemberDomainException.Code.BAD_REQUEST, "phone is required");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new MemberDomainException(MemberDomainException.Code.BAD_REQUEST, "passwordHash is required");
        }
        Member member = new Member();
        member.setMemberId(memberId);
        member.setPhone(phone.trim());
        member.setPasswordHash(passwordHash);
        member.setAvatarUrl(avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl.trim());
        member.setStatus(MemberStatus.NORMAL.name());
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        return member;
    }

    public static Member from(MemberDO source) {
        if (source == null) {
            return null;
        }
        Member member = new Member();
        member.setId(source.getId());
        member.setMemberId(source.getMemberId());
        member.setPhone(source.getPhone());
        member.setPasswordHash(source.getPasswordHash());
        member.setAvatarUrl(source.getAvatarUrl());
        member.setStatus(source.getStatus());
        member.setCreatedAt(source.getCreatedAt());
        member.setUpdatedAt(source.getUpdatedAt());
        return member;
    }

    public void requireCanLogin() {
        if (!MemberStatus.NORMAL.name().equals(getStatus())) {
            throw new MemberDomainException(MemberDomainException.Code.CONFLICT, "member cannot login");
        }
    }
}
```

Create `MemberDomainException.java`:

```java
package com.jason.demo.demo2.member.service.core;

public class MemberDomainException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        CONFLICT,
        BAD_REQUEST
    }

    private final Code code;

    public MemberDomainException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
```

Create `PasswordHasher.java`:

```java
package com.jason.demo.demo2.member.service.core;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String hash(String password) {
        if (password == null || password.isBlank()) {
            throw new MemberDomainException(MemberDomainException.Code.BAD_REQUEST, "password is required");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        return "pbkdf2$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    public boolean matches(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            return false;
        }
        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = pbkdf2(password.toCharArray(), salt, iterations, expected.length * 8);
        return java.security.MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash password", e);
        }
    }
}
```

- [ ] **Step 4: Implement mapper, converter, repository, and domain service**

Create `MemberMapper.java`:

```java
package com.jason.demo.demo2.member.service.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseMapper<MemberDO> {
}
```

Create `MemberDoConvert.java`:

```java
package com.jason.demo.demo2.member.service.infrastructure.repository.convert;

import com.jason.demo.demo2.member.service.core.domain.Member;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberDoConvert {

    MemberDO toDo(Member member);

    default Member toDomain(MemberDO memberDO) {
        return Member.from(memberDO);
    }
}
```

Create `MemberRepository.java`:

```java
package com.jason.demo.demo2.member.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.member.service.core.domain.Member;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;
import com.jason.demo.demo2.member.service.infrastructure.dao.mapper.MemberMapper;
import com.jason.demo.demo2.member.service.infrastructure.repository.convert.MemberDoConvert;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MemberRepository {

    private final MemberMapper memberMapper;
    private final MemberDoConvert memberDoConvert;

    public MemberRepository(MemberMapper memberMapper, MemberDoConvert memberDoConvert) {
        this.memberMapper = memberMapper;
        this.memberDoConvert = memberDoConvert;
    }

    public void insert(Member member) {
        memberMapper.insert(memberDoConvert.toDo(member));
    }

    public Optional<Member> findByPhone(String phone) {
        MemberDO row = memberMapper.selectOne(new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getPhone, phone));
        return Optional.ofNullable(memberDoConvert.toDomain(row));
    }

    public Optional<Member> findByMemberId(long memberId) {
        MemberDO row = memberMapper.selectOne(new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getMemberId, memberId));
        return Optional.ofNullable(memberDoConvert.toDomain(row));
    }
}
```

Create `MemberDomainService.java`:

```java
package com.jason.demo.demo2.member.service.core;

import com.jason.demo.demo2.member.service.core.domain.Member;
import com.jason.demo.demo2.member.service.infrastructure.repository.MemberRepository;
import org.springframework.stereotype.Service;

@Service
public class MemberDomainService {

    private final MemberRepository memberRepository;

    public MemberDomainService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public void register(Member member) {
        memberRepository.findByPhone(member.getPhone()).ifPresent(existing -> {
            throw new MemberDomainException(MemberDomainException.Code.CONFLICT, "phone already registered");
        });
        memberRepository.insert(member);
    }

    public Member requireLoginMember(String phone) {
        Member member = memberRepository.findByPhone(phone)
                .orElseThrow(() -> new MemberDomainException(MemberDomainException.Code.NOT_FOUND, "member not found"));
        member.requireCanLogin();
        return member;
    }

    public Member requireByMemberId(long memberId) {
        return memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new MemberDomainException(MemberDomainException.Code.NOT_FOUND, "member not found"));
    }
}
```

- [ ] **Step 5: Implement member command executors**

Create `MemberRegisterCmdExe.java`, `MemberLoginCmdExe.java`, `MemberLogoutCmdExe.java`, and `MemberGetProfileCmdExe.java` with these bodies:

```java
// MemberRegisterCmdExe
@Service
public class MemberRegisterCmdExe {
    private final MemberDomainService memberDomainService;
    private final SnowflakeIdGenerator idGenerator;
    private final PasswordHasher passwordHasher;

    public MemberRegisterCmdExe(MemberDomainService memberDomainService, SnowflakeIdGenerator idGenerator, PasswordHasher passwordHasher) {
        this.memberDomainService = memberDomainService;
        this.idGenerator = idGenerator;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public Member execute(String phone, String password, String avatarUrl) {
        Member member = Member.create(idGenerator.nextId(), phone, passwordHasher.hash(password), avatarUrl, LocalDateTime.now());
        memberDomainService.register(member);
        return member;
    }
}
```

```java
// MemberLoginCmdExe
@Service
public class MemberLoginCmdExe {
    private final MemberDomainService memberDomainService;
    private final PasswordHasher passwordHasher;
    private final AuthSessionService authSessionService;

    public MemberLoginCmdExe(MemberDomainService memberDomainService, PasswordHasher passwordHasher, AuthSessionService authSessionService) {
        this.memberDomainService = memberDomainService;
        this.passwordHasher = passwordHasher;
        this.authSessionService = authSessionService;
    }

    public AuthSession execute(String phone, String password) {
        Member member = memberDomainService.requireLoginMember(phone);
        if (!passwordHasher.matches(password, member.getPasswordHash())) {
            throw new MemberDomainException(MemberDomainException.Code.BAD_REQUEST, "password error");
        }
        AuthSession session = authSessionService.createSession(member.getMemberId(), member.getPhone(), member.getAvatarUrl());
        session.setPhone(member.getPhone());
        session.setAvatarUrl(member.getAvatarUrl());
        return session;
    }
}
```

```java
// MemberLogoutCmdExe
@Service
public class MemberLogoutCmdExe {
    private final AuthSessionService authSessionService;

    public MemberLogoutCmdExe(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    public boolean execute(String token) {
        return authSessionService.deleteSession(token);
    }
}
```

```java
// MemberGetProfileCmdExe
@Service
public class MemberGetProfileCmdExe {
    private final MemberDomainService memberDomainService;

    public MemberGetProfileCmdExe(MemberDomainService memberDomainService) {
        this.memberDomainService = memberDomainService;
    }

    public Member execute() {
        return memberDomainService.requireByMemberId(LoginContextHolder.require().memberId());
    }
}
```

- [ ] **Step 6: Implement VOs, converter, support, and controller**

Use Lombok `@Data` for VOs. Required fields:

```java
// RegisterMemberReqVO
private String phone;
private String password;
private String avatarUrl;

// LoginMemberReqVO
private String phone;
private String password;

// DeleteSessionReqVO
private String token;

// RegisterMemberResVO and GetMemberProfileResVO
private Long memberId;
private String phone;
private String avatarUrl;
private String status;

// LoginMemberResVO
private String token;
private Long memberId;
private String phone;
private String avatarUrl;
private long expiresInSeconds;

// LogoutMemberResVO and DeleteSessionResVO
private boolean success;
```

Create `MemberVoConvert.java`:

```java
package com.jason.demo.demo2.member.app.convert;

import com.jason.demo.demo2.framework.auth.AuthSession;
import com.jason.demo.demo2.member.app.vo.res.GetMemberProfileResVO;
import com.jason.demo.demo2.member.app.vo.res.LoginMemberResVO;
import com.jason.demo.demo2.member.app.vo.res.RegisterMemberResVO;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberVoConvert {

    RegisterMemberResVO toRegisterRes(Member member);

    GetMemberProfileResVO toProfileRes(Member member);

    LoginMemberResVO toLoginRes(AuthSession session);
}
```

Create `MemberHttpSupport.java` matching `OrderHttpSupport`, mapping `NOT_FOUND -> 404`, `CONFLICT -> 409`, `BAD_REQUEST -> 400`.

Create `MemberController.java` with:

```java
@RestController
@RequestMapping("/demo/members")
public class MemberController {
    @PostMapping("/register")
    public RegisterMemberResVO register(@RequestBody RegisterMemberReqVO request)

    @PostMapping("/login")
    public LoginMemberResVO login(@RequestBody LoginMemberReqVO request)

    @LoginRequired
    @PostMapping("/logout")
    public LogoutMemberResVO logout()

    @LoginRequired
    @PostMapping("/getProfile")
    public GetMemberProfileResVO getProfile()

    @PostMapping("/deleteSession")
    public DeleteSessionResVO deleteSession(@RequestBody DeleteSessionReqVO request)
}
```

Controller validation rules:

- `register`: `phone` and `password` are required.
- `login`: `phone` and `password` are required.
- `logout`: token comes from `LoginContextHolder.require().token()`.
- `deleteSession`: `token` is required.

- [ ] **Step 7: Run member tests**

Run:

```powershell
cd demo2; .\mvnw.cmd -Dtest=MemberCmdExeTest test
```

Expected: `MemberCmdExeTest` passes.

- [ ] **Step 8: Commit checkpoint**

Use this message when committing is allowed in the execution session:

```bash
feat(demo2): add member registration and login
```

---

### Task 3: Order Authentication and Ownership

**Files:**
- Modify order files listed in File Structure.
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java`

**Interfaces:**
- Consumes: `LoginContextHolder.require().memberId()`.
- Produces: owner-aware order operations without direct `member` dependency.

- [ ] **Step 1: Extend order schema and entity**

In `delay-order-schema.sql`, update `demo_order` create table to include:

```sql
    member_id   BIGINT        NOT NULL COMMENT '下单会员ID（雪花）',
```

Add index:

```sql
    INDEX idx_demo_order_member (member_id),
```

Append sync SQL for existing tables:

```sql
-- 同步注释：已有 demo_order 表增加会员归属字段
ALTER TABLE demo_order ADD COLUMN member_id BIGINT NULL COMMENT '下单会员ID（雪花）' AFTER order_id;
CREATE INDEX idx_demo_order_member ON demo_order (member_id);
```

Modify `OrderDO.java`:

```java
private Long memberId;
```

- [ ] **Step 2: Update order domain construction and copy**

Change `Order.create` signature:

```java
public static Order create(long orderId, long memberId, BigDecimal amount, LocalDateTime now)
```

Set `memberId`, and copy it in `from(OrderDO source)`:

```java
order.setMemberId(source.getMemberId());
```

- [ ] **Step 3: Update repository and domain service ownership methods**

In `OrderRepository`, add:

```java
public Optional<Order> findByIdAndMemberId(long orderId, long memberId) {
    OrderDO row = orderMapper.selectOne(new LambdaQueryWrapper<OrderDO>()
            .eq(OrderDO::getOrderId, orderId)
            .eq(OrderDO::getMemberId, memberId));
    return Optional.ofNullable(orderDoConvert.toDomain(row));
}

public boolean markPaid(long orderId, long memberId) {
    LocalDateTime now = LocalDateTime.now();
    int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
            .eq(OrderDO::getOrderId, orderId)
            .eq(OrderDO::getMemberId, memberId)
            .eq(OrderDO::getStatus, OrderStatus.PENDING_PAY.name())
            .set(OrderDO::getStatus, OrderStatus.PAID.name())
            .set(OrderDO::getUpdatedAt, now));
    return rows > 0;
}

public boolean markCancelled(long orderId, long memberId) {
    LocalDateTime now = LocalDateTime.now();
    int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
            .eq(OrderDO::getOrderId, orderId)
            .eq(OrderDO::getMemberId, memberId)
            .eq(OrderDO::getStatus, OrderStatus.PENDING_PAY.name())
            .set(OrderDO::getStatus, OrderStatus.CANCELLED.name())
            .set(OrderDO::getUpdatedAt, now));
    return rows > 0;
}
```

Keep existing `markCancelled(long orderId)` for delay expiration because the delayed task runs without a login context.

In `OrderDomainService`, add:

```java
public Order requireOrder(long orderId, long memberId) {
    return orderRepository.findByIdAndMemberId(orderId, memberId)
            .orElseThrow(() -> new OrderDomainException(OrderDomainException.Code.NOT_FOUND, "order not found"));
}

public void payOrder(long orderId, long memberId) {
    Order order = requireOrder(orderId, memberId);
    order.pay();
    if (!orderRepository.markPaid(orderId, memberId)) {
        Order latest = requireOrder(orderId, memberId);
        throw new OrderDomainException(OrderDomainException.Code.CONFLICT, "cannot pay order in status " + latest.getStatus());
    }
}

public void manualCancel(long orderId, long memberId) {
    Order order = requireOrder(orderId, memberId);
    if (!order.cancel()) {
        throw new OrderDomainException(OrderDomainException.Code.CONFLICT, "cannot cancel order in status " + order.getStatus());
    }
    if (!orderRepository.markCancelled(orderId, memberId)) {
        Order latest = requireOrder(orderId, memberId);
        throw new OrderDomainException(OrderDomainException.Code.CONFLICT, "cannot cancel order in status " + latest.getStatus());
    }
}
```

- [ ] **Step 4: Update executors to use login context**

In `OrderPlaceCmdExe.execute`, read:

```java
long memberId = LoginContextHolder.require().memberId();
Order order = Order.create(orderId, memberId, amount, LocalDateTime.now());
```

In pay/get/cancel executors:

```java
long memberId = LoginContextHolder.require().memberId();
```

Use:

```java
orderDomainService.payOrder(orderId, memberId);
return orderDomainService.requireOrder(orderId, memberId);
```

```java
return orderDomainService.requireOrder(orderId, memberId);
```

```java
orderDomainService.manualCancel(orderId, memberId);
return orderDomainService.requireOrder(orderId, memberId);
```

- [ ] **Step 5: Add `@LoginRequired` to order controller**

Annotate `orderPlace`, `pay`, `get`, and `cancel`:

```java
@LoginRequired
@PostMapping("/orderPlace")
```

Repeat for `/pay`, `/get`, and `/cancel`.

- [ ] **Step 6: Update order tests**

In `OrderCmdExeTest`, wrap each executor call with:

```java
LoginContextHolder.set(new LoginPrincipal(9001L, "13888999999", "t1"));
```

Clear in `@AfterEach`.

Update verification for placement:

```java
verify(orderDomainService).place(argThat(o -> o.getOrderId() == 55L
        && o.getMemberId() == 9001L
        && OrderStatus.PENDING_PAY.name().equals(o.getStatus())));
```

Update pay/cancel stubbing:

```java
when(orderDomainService.requireOrder(55L, 9001L)).thenReturn(paid);
verify(orderDomainService).payOrder(55L, 9001L);
```

```java
when(orderDomainService.requireOrder(55L, 9001L)).thenReturn(cancelled);
verify(orderDomainService).manualCancel(55L, 9001L);
```

- [ ] **Step 7: Run order tests**

Run:

```powershell
cd demo2; .\mvnw.cmd -Dtest=OrderCmdExeTest test
```

Expected: `OrderCmdExeTest` passes.

- [ ] **Step 8: Commit checkpoint**

Use this message when committing is allowed in the execution session:

```bash
feat(demo2): require member login for orders
```

---

### Task 4: Member C-Side Demo Tab

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/src/main/resources/static/js/tabs/order-delay.js`
- Create: `demo2/src/main/resources/static/js/tabs/member.js`
- Create: `demo2/src/main/resources/static/css/tabs/member.css`

**Interfaces:**
- Consumes: `/demo/members/*`, `/demo/orders/pay`.
- Produces: a new static tab with home, order, and profile sub-tabs.

- [ ] **Step 1: Add index assets and tab shell**

In `index.html`, add CSS link near other tab CSS:

```html
<link rel="stylesheet" href="/css/tabs/member.css">
```

Add tab button near order-delay:

```html
<button class="tab-btn" data-tab="member" onclick="switchTab('member')">👤 会员 C 端 Demo</button>
```

Add tab content before closing app container:

```html
<div id="tab-member" class="tab-content">
    <div class="member-demo-header">
        <h1>会员 C 端 Demo</h1>
        <p>手机号密码登录 + Redis 登录态 + 订单接口鉴权演示</p>
    </div>
    <div class="member-demo-body">
        <section class="member-phone">
            <div class="member-phone-screen">
                <div class="member-phone-page" id="memberPhonePage"></div>
                <nav class="member-bottom-nav">
                    <button onclick="memberSwitchMobileTab('home')" id="memberNavHome">首页</button>
                    <button onclick="memberSwitchMobileTab('orders')" id="memberNavOrders">订单</button>
                    <button onclick="memberSwitchMobileTab('me')" id="memberNavMe">我的</button>
                </nav>
            </div>
        </section>
        <section class="member-side-panel">
            <div class="card">
                <div class="card-title">当前登录态</div>
                <div class="card-body">
                    <div id="memberSessionBox" class="result-box">未登录</div>
                    <button class="btn" onclick="memberLoadProfile()">访问个人中心接口</button>
                    <button class="btn" onclick="memberDeleteSession()">删除 Redis 登录态</button>
                    <button class="btn btn-primary" onclick="memberSimulatePay()">模拟支付</button>
                </div>
            </div>
            <div class="card">
                <div class="card-title">操作日志</div>
                <div class="card-body">
                    <div id="memberLog" class="result-box member-log">操作日志</div>
                </div>
            </div>
        </section>
    </div>
</div>
```

Add script:

```html
<script src="/js/tabs/member.js"></script>
```

- [ ] **Step 2: Implement CSS**

Create `member.css` with:

```css
.member-demo-header {
    background: linear-gradient(135deg, #7c3aed 0%, #ec4899 100%);
    padding: 20px;
    color: white;
}
.member-demo-header h1 { font-size: 22px; font-weight: 600; }
.member-demo-header p { font-size: 13px; opacity: 0.9; margin-top: 4px; }
.member-demo-body {
    padding: 24px;
    display: grid;
    grid-template-columns: 360px 1fr;
    gap: 20px;
}
.member-phone {
    display: flex;
    justify-content: center;
}
.member-phone-screen {
    width: 320px;
    height: 620px;
    border: 10px solid #111827;
    border-radius: 32px;
    background: #f8fafc;
    overflow: hidden;
    display: flex;
    flex-direction: column;
}
.member-phone-page {
    flex: 1;
    overflow: auto;
    padding: 16px;
}
.member-bottom-nav {
    height: 58px;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    border-top: 1px solid #e5e7eb;
    background: white;
}
.member-bottom-nav button {
    border: 0;
    background: white;
    font-weight: 600;
    color: #64748b;
}
.member-bottom-nav button.active {
    color: #7c3aed;
}
.member-user-card {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 16px;
    background: white;
    border-radius: 18px;
    box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
    cursor: pointer;
}
.member-avatar {
    width: 54px;
    height: 54px;
    border-radius: 50%;
    object-fit: cover;
    background: #e5e7eb;
}
.member-products,
.member-orders {
    display: grid;
    gap: 12px;
}
.member-product-card,
.member-order-card {
    background: white;
    border-radius: 16px;
    padding: 14px;
    box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
}
.member-auth-form {
    display: grid;
    gap: 8px;
    margin-top: 16px;
}
.member-auth-form input {
    padding: 10px;
    border: 1px solid #d1d5db;
    border-radius: 10px;
}
.member-side-panel {
    display: flex;
    flex-direction: column;
    gap: 16px;
}
.member-log {
    max-height: 360px;
    overflow: auto;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
    white-space: pre-wrap;
}
```

- [ ] **Step 3: Implement member JavaScript**

Create `member.js` with state and helpers:

```javascript
let memberToken = localStorage.getItem('demo2MemberToken') || '';
let memberProfile = null;
let memberMobileTab = 'home';

function memberDefaultAvatar() {
    return 'data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><rect width="96" height="96" rx="48" fill="#e5e7eb"/><circle cx="48" cy="36" r="16" fill="#94a3b8"/><path d="M20 82c6-18 50-18 56 0" fill="#94a3b8"/></svg>');
}

function memberAppendLog(message) {
    const box = document.getElementById('memberLog');
    const line = '[' + new Date().toLocaleTimeString() + '] ' + message;
    box.textContent = (box.textContent && !box.textContent.startsWith('操作日志') ? box.textContent + '\n' : '') + line;
}

function memberHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (memberToken) {
        headers.Authorization = 'Bearer ' + memberToken;
    }
    return headers;
}

async function memberPost(url, body) {
    return fetch(url, { method: 'POST', headers: memberHeaders(), body: JSON.stringify(body || {}) });
}
```

Add render functions:

```javascript
function memberSwitchMobileTab(tab) {
    memberMobileTab = tab;
    memberRender();
}

function memberRender() {
    document.getElementById('memberNavHome').classList.toggle('active', memberMobileTab === 'home');
    document.getElementById('memberNavOrders').classList.toggle('active', memberMobileTab === 'orders');
    document.getElementById('memberNavMe').classList.toggle('active', memberMobileTab === 'me');
    if (memberMobileTab === 'home') memberRenderHome();
    if (memberMobileTab === 'orders') memberRenderOrders();
    if (memberMobileTab === 'me') memberRenderMe();
    memberRenderSession();
}

function memberRenderHome() {
    document.getElementById('memberPhonePage').innerHTML =
        '<h2>首页</h2><div class="member-products">' +
        '<div class="member-product-card"><strong>拿铁</strong><p>静态商品，¥18.00</p></div>' +
        '<div class="member-product-card"><strong>生椰拿铁</strong><p>静态商品，¥20.00</p></div>' +
        '<div class="member-product-card"><strong>芝士蛋糕</strong><p>静态商品，¥16.00</p></div>' +
        '</div>';
}

function memberRenderOrders() {
    document.getElementById('memberPhonePage').innerHTML =
        '<h2>订单</h2><div class="member-orders">' +
        '<div class="member-order-card"><strong>我的订单</strong><p>当前为静态展示，后续接真实订单列表接口。</p></div>' +
        '</div>';
}

function memberRenderMe() {
    const avatar = memberProfile && memberProfile.avatarUrl ? memberProfile.avatarUrl : memberDefaultAvatar();
    const greeting = memberProfile ? '你好：' + memberProfile.phone : '你好，你还没登录';
    const extra = memberProfile ? '<p>memberId：' + memberProfile.memberId + '</p><button class="btn" onclick="memberLogout()">退出登录</button>' : '<p>点击此区域登录/注册</p>';
    const form = memberProfile ? '' :
        '<div class="member-auth-form">' +
        '<input id="memberPhoneInput" value="13888999999" placeholder="手机号">' +
        '<input id="memberPasswordInput" value="pwd123456" placeholder="密码" type="password">' +
        '<input id="memberAvatarInput" placeholder="头像 URL（可选）">' +
        '<button class="btn" onclick="memberRegister()">注册</button>' +
        '<button class="btn btn-primary" onclick="memberLogin()">登录</button>' +
        '</div>';
    document.getElementById('memberPhonePage').innerHTML =
        '<h2>我的</h2><div class="member-user-card" onclick="memberFocusLogin()">' +
        '<img class="member-avatar" src="' + avatar + '">' +
        '<div><strong>' + greeting + '</strong>' + extra + '</div></div>' + form;
}
```

Add actions:

```javascript
function memberFocusLogin() {
    const input = document.getElementById('memberPhoneInput');
    if (input) input.focus();
}

async function memberRegister() {
    const res = await memberPost('/demo/members/register', {
        phone: document.getElementById('memberPhoneInput').value.trim(),
        password: document.getElementById('memberPasswordInput').value,
        avatarUrl: document.getElementById('memberAvatarInput').value.trim()
    });
    const text = await res.text();
    memberAppendLog('注册：' + text);
}

async function memberLogin() {
    const res = await memberPost('/demo/members/login', {
        phone: document.getElementById('memberPhoneInput').value.trim(),
        password: document.getElementById('memberPasswordInput').value
    });
    const text = await res.text();
    if (!res.ok) {
        memberAppendLog('登录失败：' + text);
        return;
    }
    const data = JSON.parse(text);
    memberToken = data.token;
    memberProfile = data;
    localStorage.setItem('demo2MemberToken', memberToken);
    memberAppendLog('登录成功：' + data.phone);
    memberRender();
}

async function memberLoadProfile() {
    const res = await memberPost('/demo/members/getProfile', {});
    const text = await res.text();
    memberAppendLog('个人中心：' + text);
    if (res.ok) {
        memberProfile = JSON.parse(text);
        memberRender();
    }
}

async function memberLogout() {
    const res = await memberPost('/demo/members/logout', {});
    memberAppendLog('退出登录：' + await res.text());
    memberToken = '';
    memberProfile = null;
    localStorage.removeItem('demo2MemberToken');
    memberRender();
}

async function memberDeleteSession() {
    if (!memberToken) {
        memberAppendLog('当前无 token 可删除');
        return;
    }
    const res = await memberPost('/demo/members/deleteSession', { token: memberToken });
    memberAppendLog('删除 Redis 登录态：' + await res.text());
}

async function memberSimulatePay() {
    const orderId = prompt('输入 orderId 进行模拟支付');
    if (!orderId) return;
    const res = await memberPost('/demo/orders/pay', { orderId: Number(orderId) });
    memberAppendLog('模拟支付：' + await res.text());
}

function memberRenderSession() {
    const box = document.getElementById('memberSessionBox');
    if (!box) return;
    box.textContent = memberToken
        ? 'token=' + memberToken + '\nRedis key=demo2:auth:session:' + memberToken + '\nphone=' + (memberProfile && memberProfile.phone ? memberProfile.phone : '')
        : '未登录';
}

memberRender();
if (memberToken) {
    memberLoadProfile();
}
```

- [ ] **Step 4: Reuse token in order-delay demo**

Modify `orderDelayJsonPost`:

```javascript
async function orderDelayJsonPost(url, body) {
    const headers = { 'Content-Type': 'application/json' };
    const token = localStorage.getItem('demo2MemberToken');
    if (token) {
        headers.Authorization = 'Bearer ' + token;
    }
    return fetch(url, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(body)
    });
}
```

- [ ] **Step 5: Manual frontend verification**

Run the app, open `/`, switch to `会员 C 端 Demo`, and verify:

- 未登录时“我的”显示默认头像和 `你好，你还没登录`。
- 首页显示 three static product cards.
- 订单页显示 static order placeholder.
- 注册 `13888999999` with `pwd123456` succeeds.
- 登录后“我的” shows `你好：13888999999`.
- 右侧 shows token and Redis key.
- 删除 Redis 登录态 then `访问个人中心接口` returns 401 and log records failure.

- [ ] **Step 6: Commit checkpoint**

Use this message when committing is allowed in the execution session:

```bash
feat(demo2): add member c-side demo tab
```

---

### Task 5: Full Verification and Documentation

**Files:**
- Modify: `demo2/README.md` if the current README has a demo setup section for `delay-order-schema.sql`.
- Verify: all files changed by Tasks 1-4.

**Interfaces:**
- Consumes: completed backend and frontend tasks.
- Produces: test evidence and concise manual instructions.

- [ ] **Step 1: Run focused unit tests**

Run:

```powershell
cd demo2; .\mvnw.cmd -Dtest=LoginContextHolderTest,AuthSessionServiceTest,LoginRequiredInterceptorTest,MemberCmdExeTest,OrderCmdExeTest test
```

Expected: all focused tests pass.

- [ ] **Step 2: Run a package-level test slice**

Run:

```powershell
cd demo2; .\mvnw.cmd -Dtest="com.jason.demo.demo2.framework.auth.*Test,com.jason.demo.demo2.member.*Test,com.jason.demo.demo2.order.*Test" test
```

Expected: auth, member, and order tests pass.

- [ ] **Step 3: Run compile**

Run:

```powershell
cd demo2; .\mvnw.cmd -DskipTests compile
```

Expected: compile succeeds, including MapStruct generated mappers.

- [ ] **Step 4: Update README demo notes**

If `README.md` mentions `src/main/resources/db/delay-order-schema.sql`, add the member module note:

```markdown
会员 C 端 Demo 依赖 MySQL 表 `demo_member` 和订单表字段 `demo_order.member_id`。
新环境可执行 `src/main/resources/db/delay-order-schema.sql`。
已有环境需要执行脚本底部的同步 SQL，给 `demo_order` 增加 `member_id` 字段。
Redis 用于登录态，默认 key 前缀为 `demo2:auth:session:`，TTL 为 `app.auth.session-ttl=24h`。
```

- [ ] **Step 5: Manual API smoke test**

With app running and Redis/MySQL ready:

```powershell
$register = Invoke-RestMethod -Method Post -Uri http://localhost:8080/demo/members/register -ContentType 'application/json' -Body '{"phone":"13888999999","password":"pwd123456","avatarUrl":""}'
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/demo/members/login -ContentType 'application/json' -Body '{"phone":"13888999999","password":"pwd123456"}'
$token = $login.token
Invoke-RestMethod -Method Post -Uri http://localhost:8080/demo/members/getProfile -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body '{}'
```

Expected:

- register returns `memberId`, `phone`, `avatarUrl`, `status`.
- login returns `token`, `memberId`, `phone`, `avatarUrl`, `expiresInSeconds`.
- getProfile returns current member profile.

- [ ] **Step 6: Manual Redis invalidation smoke test**

Run:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/demo/members/deleteSession -ContentType 'application/json' -Body "{`"token`":`"$token`"}"
Invoke-WebRequest -Method Post -Uri http://localhost:8080/demo/members/getProfile -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body '{}'
```

Expected: second command returns HTTP 401.

- [ ] **Step 7: Commit checkpoint**

Use this message when committing is allowed in the execution session:

```bash
docs(demo2): document member demo verification
```

---

## Self-Review Checklist

- Spec coverage: authentication framework, member module, Redis sessions, order ownership, frontend Tab, and tests are covered by Tasks 1-5.
- Dependency direction: `framework.auth` has no business imports; `member` and `order` depend on `framework.auth`.
- Type consistency: plan uses `LoginPrincipal.memberId()`, `AuthSession.getMemberId()`, `AuthSession.getAvatarUrl()`, `Member.getMemberId()`, `Member.getAvatarUrl()`, and `OrderDO.memberId`.
- Scope: SMS login, JWT, real products, and real order list are excluded.
