package com.jason.demo.demo2.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.order")
public class OrderProperties {

    /** 预览 placeToken TTL，默认 30 分钟。 */
    private Duration placeTokenTtl = Duration.ofMinutes(30);
}
