package com.jason.demo.demo2.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 项目含 webflux 时，默认 RestClient 会走 Reactor Netty，读超时偏短（约 30s），
 * 调用 ElevenLabs TTS/STT 易出现 ReadTimeoutException。
 * 统一改用 JDK HttpURLConnection 并放宽超时。
 */
@Configuration
public class ElevenLabsHttpClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    @Bean
    @ConditionalOnMissingBean
    RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }
}
