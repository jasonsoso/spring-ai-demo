package com.jason.demo.demo2.order.app.support;

import com.jason.demo.demo2.order.service.core.OrderDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class OrderHttpSupport {

    private OrderHttpSupport() {
    }

    public static ResponseStatusException toHttpException(OrderDomainException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return new ResponseStatusException(status, ex.getMessage());
    }
}
