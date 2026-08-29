package com.jason.demo.demo2.framework.cache.configuration;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableMethodCache(basePackages = "com.jason.demo.demo2")
public class JetCacheConfiguration {
}
