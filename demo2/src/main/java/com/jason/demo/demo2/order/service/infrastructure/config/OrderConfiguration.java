package com.jason.demo.demo2.order.service.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrderProperties.class)
public class OrderConfiguration {
}
