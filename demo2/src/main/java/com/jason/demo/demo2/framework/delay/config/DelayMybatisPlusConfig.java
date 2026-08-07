package com.jason.demo.demo2.framework.delay.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DelayProperties.class)
@MapperScan({
        "com.jason.demo.demo2.framework.delay.repository",
        "com.jason.demo.demo2.order.repository"
})
public class DelayMybatisPlusConfig {
}
