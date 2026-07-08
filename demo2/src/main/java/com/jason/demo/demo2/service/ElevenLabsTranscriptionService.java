package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.ElevenLabsVoiceProperties;
import com.jason.demo.demo2.model.SttTranscribeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Service
public class ElevenLabsTranscriptionService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final ElevenLabsVoiceProperties voiceProperties;
    private final String apiKey;

    public ElevenLabsTranscriptionService(
            JsonMapper jsonMapper,
            ElevenLabsVoiceProperties voiceProperties,
            @Value("${spring.ai.elevenlabs.api-key:}") String apiKey) {
        this.jsonMapper = jsonMapper;
        this.voiceProperties = voiceProperties;
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl("https://api.elevenlabs.io")
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    public SttTranscribeResponse transcribe(MultipartFile file, String languageCode) throws IOException {
        requireConfigured();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("音频文件不能为空");
        }

        String lang = StringUtils.hasText(languageCode)
                ? languageCode
                : voiceProperties.getStt().getLanguageCode();

        byte[] bytes = file.getBytes();
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                String name = file.getOriginalFilename();
                return StringUtils.hasText(name) ? name : "recording.webm";
            }
        }).contentType(MediaType.parseMediaType(
                StringUtils.hasText(file.getContentType()) ? file.getContentType() : "audio/webm"));
        bodyBuilder.part("model_id", voiceProperties.getStt().getModelId());
        bodyBuilder.part("language_code", lang);

        try {
            String responseBody = restClient.post()
                    .uri("/v1/speech-to-text")
                    .header("xi-api-key", apiKey)
                    .body(bodyBuilder.build())
                    .retrieve()
                    .body(String.class);

            return parseResponse(responseBody, lang);
        } catch (RestClientResponseException ex) {
            log.warn("ElevenLabs STT failed: status={} body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new IllegalStateException("ElevenLabs 语音识别失败 ("
                    + ex.getStatusCode().value() + "): " + extractApiError(ex.getResponseBodyAsString()));
        } catch (ResourceAccessException ex) {
            log.warn("ElevenLabs STT network error", ex);
            throw new IllegalStateException("ElevenLabs 语音识别网络超时或连接中断，请稍后重试");
        } catch (RestClientException ex) {
            log.warn("ElevenLabs STT client error", ex);
            throw new IllegalStateException("ElevenLabs 语音识别请求失败: " + ex.getMessage());
        }
    }

    private SttTranscribeResponse parseResponse(String responseBody, String defaultLang) throws IOException {
        JsonNode root = jsonMapper.readTree(responseBody);
        String text = extractText(root);
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("ElevenLabs 返回空转写结果");
        }
        String language = root.hasNonNull("language_code")
                ? root.get("language_code").asText()
                : defaultLang;
        return new SttTranscribeResponse(text.trim(), language);
    }

    private String extractText(JsonNode root) {
        if (root.hasNonNull("text")) {
            return root.get("text").asText();
        }
        if (root.has("transcripts") && root.get("transcripts").isArray() && !root.get("transcripts").isEmpty()) {
            JsonNode first = root.get("transcripts").get(0);
            if (first.hasNonNull("text")) {
                return first.get("text").asText();
            }
        }
        return null;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("请设置环境变量 ELEVENLABS_API_KEY 并重启应用");
        }
    }

    private String extractApiError(String body) {
        if (!StringUtils.hasText(body)) {
            return "无详细信息";
        }
        try {
            JsonNode root = jsonMapper.readTree(body);
            if (root.hasNonNull("detail")) {
                JsonNode detail = root.get("detail");
                if (detail.isTextual()) {
                    return detail.asText();
                }
                if (detail.hasNonNull("message")) {
                    return detail.get("message").asText();
                }
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
