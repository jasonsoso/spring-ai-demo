package com.jason.demo.demo2.member.app.support;

import com.jason.demo.demo2.member.service.core.MemberDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class MemberHttpSupport {

    private MemberHttpSupport() {
    }

    public static ResponseStatusException toHttpException(MemberDomainException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return new ResponseStatusException(status, ex.getMessage());
    }
}
