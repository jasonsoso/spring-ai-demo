package com.jason.demo.demo2.framework.auth.context;

public record LoginPrincipal(Long memberId, String phone, String token) {
}
