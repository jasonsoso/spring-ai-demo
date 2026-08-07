package com.jason.demo.demo2.framework.delay.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 延时任务与订单 Demo 的 MyBatis Mapper 扫描，并启用调度与 {@link DelayProperties}。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DelayProperties.class)
@MapperScan({
        "com.jason.demo.demo2.framework.delay.repository",
        "com.jason.demo.demo2.order.repository"
})
public class DelayMybatisPlusConfig {
}
