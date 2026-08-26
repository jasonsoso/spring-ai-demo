package com.jason.demo.demo2.framework.auth;

import com.jason.demo.demo2.framework.auth.annotation.LoginRequired;
import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
import com.jason.demo.demo2.framework.auth.model.AuthSession;
import com.jason.demo.demo2.framework.auth.service.AuthSessionService;
import com.jason.demo.demo2.framework.auth.web.LoginRequiredInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        AuthSession session = new AuthSession(
                "abc",
                1001L,
                "13888999999",
                null,
                LocalDateTime.now(),
                86400L);
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

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(mock(HttpServletRequest.class), mock(HttpServletResponse.class),
                        handler("protectedEndpoint")));

        assertEquals(CommonErrorCodeEnum.INVALID_TOKEN.getCode(), ex.getCode());
    }

    @Test
    void afterCompletionClearsContext() throws Exception {
        LoginRequiredInterceptor interceptor = new LoginRequiredInterceptor(mock(AuthSessionService.class));
        LoginContextHolder.set(new LoginPrincipal(1001L, "13888999999", "abc"));

        interceptor.afterCompletion(mock(HttpServletRequest.class), mock(HttpServletResponse.class),
                handler("protectedEndpoint"), null);

        assertNull(LoginContextHolder.get());
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
