package com.jason.demo.demo2.framework.delay.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 延时任务模块配置：启用调度与 {@link DelayProperties}。
 * MyBatis Mapper 扫描见 {@link com.jason.demo.demo2.framework.mybatis.configuration.MybatisPlusConfiguration}。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DelayProperties.class)
public class DelayConfiguration {
}
