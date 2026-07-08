package com.jason.demo.demo2.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent.voice-chat")
public class ElevenLabsVoiceProperties {

    private boolean enabled = true;

    private Stt stt = new Stt();

    private Tts tts = new Tts();

    @Data
    public static class Stt {
        private boolean enabled = true;
        private String modelId = "scribe_v2";
        private String languageCode = "zh";
    }

    @Data
    public static class Tts {
        private int sentenceMaxChars = 40;
    }
}
