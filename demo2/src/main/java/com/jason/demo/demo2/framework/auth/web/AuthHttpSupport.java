package com.jason.demo.demo2.framework.auth.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuthHttpSupport {

    private AuthHttpSupport() {
    }

    public static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
