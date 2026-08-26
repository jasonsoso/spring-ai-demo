package com.jason.demo.demo2.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI demo2OpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Demo2 AI API")
                        .description("Spring AI 与业务 Demo API（会员/订单/商品）；统一 JsonResult 包装")
                        .version("v1.0"));
    }
}
