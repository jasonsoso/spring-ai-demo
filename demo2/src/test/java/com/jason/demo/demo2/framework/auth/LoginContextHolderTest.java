package com.jason.demo.demo2.framework.auth;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
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
