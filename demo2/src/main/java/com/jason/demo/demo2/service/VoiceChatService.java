package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.ElevenLabsVoiceProperties;
import com.jason.demo.demo2.model.VoiceChatSseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class VoiceChatService {

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；!?\\n]");
    private static final String MIME_MPEG = "audio/mpeg";

    private final ChatClient chatClient;
    private final TextToSpeechModel textToSpeechModel;
    private final ElevenLabsVoiceProperties voiceProperties;
    private final String defaultVoiceId;
    private final String defaultTtsModel;
    private final String defaultOutputFormat;

    public VoiceChatService(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("elevenLabsSpeechModel") TextToSpeechModel textToSpeechModel,
            ElevenLabsVoiceProperties voiceProperties,
            @Value("${spring.ai.elevenlabs.tts.voice-id:21m00Tcm4TlvDq8ikWAM}") String defaultVoiceId,
            @Value("${spring.ai.elevenlabs.tts.model-id:eleven_multilingual_v2}") String defaultTtsModel,
            @Value("${spring.ai.elevenlabs.tts.output-format:mp3_44100_128}") String defaultOutputFormat) {
        this.chatClient = chatClientBuilder.build();
        this.textToSpeechModel = textToSpeechModel;
        this.voiceProperties = voiceProperties;
        this.defaultVoiceId = defaultVoiceId;
        this.defaultTtsModel = defaultTtsModel;
        this.defaultOutputFormat = defaultOutputFormat;
    }

    public void streamChat(String message, String voiceId, boolean autoSpeak, SseEmitter emitter, JsonMapper jsonMapper) {
        String resolvedVoiceId = StringUtils.hasText(voiceId) ? voiceId : defaultVoiceId;
        SentenceBuffer buffer = new SentenceBuffer(voiceProperties.getTts().getSentenceMaxChars());

        send(emitter, jsonMapper, VoiceChatSseEvent.running());
        send(emitter, jsonMapper, VoiceChatSseEvent.userText(message));

        chatClient.prompt()
                .user(message)
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            send(emitter, jsonMapper, VoiceChatSseEvent.token(chunk));
                            if (!autoSpeak || !StringUtils.hasText(chunk)) {
                                return;
                            }
                            buffer.append(chunk);
                            for (String sentence : buffer.drainReadySentences()) {
                                synthesizeAndSend(emitter, jsonMapper, sentence, resolvedVoiceId);
                            }
                        },
                        error -> {
                            log.error("Voice chat stream failed", error);
                            send(emitter, jsonMapper, VoiceChatSseEvent.failed(error.getMessage()));
                            emitter.completeWithError(error);
                        },
                        () -> {
                            if (autoSpeak) {
                                String remaining = buffer.flush();
                                if (StringUtils.hasText(remaining)) {
                                    synthesizeAndSend(emitter, jsonMapper, remaining, resolvedVoiceId);
                                }
                            }
                            send(emitter, jsonMapper, VoiceChatSseEvent.completed());
                            emitter.complete();
                        }
                );
    }

    private void synthesizeAndSend(SseEmitter emitter, JsonMapper jsonMapper, String text, String voiceId) {
        try {
            ElevenLabsTextToSpeechOptions options = ElevenLabsTextToSpeechOptions.builder()
                    .model(defaultTtsModel)
                    .voiceId(voiceId)
                    .outputFormat(defaultOutputFormat)
                    .build();
            TextToSpeechResponse response = textToSpeechModel.call(new TextToSpeechPrompt(text, options));
            byte[] audio = response.getResult().getOutput();
            if (audio == null || audio.length == 0) {
                return;
            }
            String encoded = Base64.getEncoder().encodeToString(audio);
            send(emitter, jsonMapper, VoiceChatSseEvent.audioChunk(encoded, MIME_MPEG));
        } catch (Exception ex) {
            log.warn("TTS skipped for sentence [{}]: {}", text, ex.getMessage());
        }
    }

    private void send(SseEmitter emitter, JsonMapper jsonMapper, VoiceChatSseEvent event) {
        try {
            String json = jsonMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json).build());
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }

    static final class SentenceBuffer {

        private final int maxChars;
        private final StringBuilder buffer = new StringBuilder();

        SentenceBuffer(int maxChars) {
            this.maxChars = Math.max(10, maxChars);
        }

        void append(String chunk) {
            buffer.append(chunk);
        }

        java.util.List<String> drainReadySentences() {
            java.util.List<String> sentences = new java.util.ArrayList<>();
            while (true) {
                Matcher matcher = SENTENCE_END.matcher(buffer);
                if (matcher.find()) {
                    int end = matcher.end();
                    String sentence = buffer.substring(0, end).trim();
                    buffer.delete(0, end);
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    continue;
                }
                if (buffer.length() >= maxChars) {
                    String sentence = buffer.toString().trim();
                    buffer.setLength(0);
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                }
                break;
            }
            return sentences;
        }

        String flush() {
            String remaining = buffer.toString().trim();
            buffer.setLength(0);
            return remaining;
        }
    }
}
