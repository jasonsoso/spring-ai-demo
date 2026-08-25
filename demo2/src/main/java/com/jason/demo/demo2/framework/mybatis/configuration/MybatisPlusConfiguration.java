package com.jason.demo.demo2.framework.mybatis.configuration;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 MyBatis Mapper 扫描。
 * <p>
 * 扫描 {@code com.jason.demo.demo2} 下所有带 {@link Mapper} 的接口，
 * 业务模块按 DDD 约定放在 {@code *.service.infrastructure.dao.mapper} 即可自动注册。
 * <p>
 * 注意：{@code @MapperScan} 不支持 {@code com.jason.demo.demo2.*.service...} 这类包名通配符。
 */
@Configuration
@MapperScan(basePackages = "com.jason.demo.demo2", annotationClass = Mapper.class)
public class MybatisPlusConfiguration {
}
