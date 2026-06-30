package com.jason.demo.demo2.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * A2A Server 在根路径注册 POST / 后，RequestMappingHandlerMapping 会拦截所有访问 / 的请求；
 * ViewController 转发无法生效，GET 会直接返回 405。此处显式注册 GET / 与 A2A 的 POST / 按方法分流。
 */
@Controller
public class IndexController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
