package com.jason.demo.demo2.controller;

import com.baomidou.lock.exception.LockFailureException;
import com.jason.demo.demo2.model.LockDemoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LockDemoController.class)
public class LockDemoExceptionHandler {

    @ExceptionHandler(LockFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public LockDemoResponse onLockFailure(LockFailureException ex) {
        return LockDemoResponse.conflict();
    }
}
