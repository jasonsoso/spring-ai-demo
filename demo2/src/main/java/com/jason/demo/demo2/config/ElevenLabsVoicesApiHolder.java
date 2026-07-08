package com.jason.demo.demo2.config;

import org.springframework.ai.elevenlabs.api.ElevenLabsVoicesApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ElevenLabsVoicesApiHolder {

    private final ElevenLabsVoicesApi voicesApi;

    public ElevenLabsVoicesApiHolder(@Value("${spring.ai.elevenlabs.api-key:}") String apiKey) {
        this.voicesApi = ElevenLabsVoicesApi.builder()
                .apiKey(apiKey)
                .build();
    }

    public ElevenLabsVoicesApi getVoicesApi() {
        return voicesApi;
    }
}
