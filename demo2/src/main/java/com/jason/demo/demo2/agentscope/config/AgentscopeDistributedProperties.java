package com.jason.demo.demo2.agentscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.agentscope.distributed")
public record AgentscopeDistributedProperties(@DefaultValue("true") boolean enabled) {
}
