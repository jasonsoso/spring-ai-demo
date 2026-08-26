package com.jason.demo.demo2.framework.auth.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.jason.demo.demo2.framework.auth.web.AuthHttpSupport;

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
            throw AuthHttpSupport.unauthorized();
        }
        return principal;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
