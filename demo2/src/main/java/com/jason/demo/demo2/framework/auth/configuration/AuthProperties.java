package com.jason.demo.demo2.framework.auth.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String sessionKeyPrefix = "demo2:auth:session:";
    private Duration sessionTtl = Duration.ofHours(24);
}
