package com.jason.demo.demo2.product.service.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProductStockProperties.class)
public class ProductStockConfiguration {
}
