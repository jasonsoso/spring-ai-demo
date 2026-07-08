package com.jason.demo.demo2.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ElevenLabsVoiceProperties.class)
public class ElevenLabsVoiceConfig {
}
