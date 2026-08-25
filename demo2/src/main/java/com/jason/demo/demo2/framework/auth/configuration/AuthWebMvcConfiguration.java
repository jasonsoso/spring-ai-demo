package com.jason.demo.demo2.framework.auth.configuration;

import com.jason.demo.demo2.framework.auth.web.LoginRequiredInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthWebMvcConfiguration implements WebMvcConfigurer {

    private final LoginRequiredInterceptor loginRequiredInterceptor;

    public AuthWebMvcConfiguration(LoginRequiredInterceptor loginRequiredInterceptor) {
        this.loginRequiredInterceptor = loginRequiredInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginRequiredInterceptor).addPathPatterns("/demo/**");
    }
}
