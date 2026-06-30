package com.jason.demo.demo2.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A2A Server 在根路径注册 POST / 后，会抢占默认欢迎页映射，导致浏览器 GET / 返回 405。
 * 显式将 GET / 转发到 static/index.html，与 A2A 的 POST / 共存。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
