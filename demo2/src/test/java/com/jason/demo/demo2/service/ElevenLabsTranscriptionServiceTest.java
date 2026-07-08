package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.ElevenLabsVoiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElevenLabsTranscriptionServiceTest {

    @Test
    void isConfigured_falseWhenApiKeyBlank() {
        var service = new ElevenLabsTranscriptionService(new JsonMapper(), new ElevenLabsVoiceProperties(), "");
        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_trueWhenApiKeyPresent() {
        var service = new ElevenLabsTranscriptionService(new JsonMapper(), new ElevenLabsVoiceProperties(), "sk-test");
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void transcribe_rejectsEmptyFile() {
        var service = new ElevenLabsTranscriptionService(new JsonMapper(), new ElevenLabsVoiceProperties(), "sk-test");
        var file = new MockMultipartFile("file", "a.webm", "audio/webm", new byte[0]);
        assertThatThrownBy(() -> service.transcribe(file, "zh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
