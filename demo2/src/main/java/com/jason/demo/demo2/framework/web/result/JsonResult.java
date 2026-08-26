package com.jason.demo.demo2.framework.web.result;

import lombok.Data;

@Data
public class JsonResult<T> {

    private int code;
    private String message;
    private T data;
}
