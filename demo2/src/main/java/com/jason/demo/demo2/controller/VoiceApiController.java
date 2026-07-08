package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.config.ElevenLabsVoicesApiHolder;
import com.jason.demo.demo2.model.TextToSpeechRequest;
import com.jason.demo.demo2.model.VoiceChatRequest;
import com.jason.demo.demo2.model.VoiceChatSseEvent;
import com.jason.demo.demo2.service.ElevenLabsTranscriptionService;
import com.jason.demo.demo2.service.VoiceChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechModel;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions;
import org.springframework.ai.elevenlabs.api.ElevenLabsApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import com.jason.demo.demo2.model.SttTranscribeResponse;

import java.io.IOException;
import java.util.Map;

@Tag(name = "ElevenLabs 语音")
@RestController
@RequestMapping("/api")
public class VoiceApiController {

    private final ElevenLabsTextToSpeechModel textToSpeechModel;
    private final ElevenLabsVoicesApiHolder voicesApiHolder;
    private final ElevenLabsTranscriptionService transcriptionService;
    private final VoiceChatService voiceChatService;
    private final JsonMapper jsonMapper;
    private final String defaultTtsModel;
    private final String defaultOutputFormat;

    public VoiceApiController(
            ElevenLabsTextToSpeechModel textToSpeechModel,
            ElevenLabsVoicesApiHolder voicesApiHolder,
            ElevenLabsTranscriptionService transcriptionService,
            VoiceChatService voiceChatService,
            JsonMapper jsonMapper,
            @Value("${spring.ai.elevenlabs.tts.model-id:eleven_multilingual_v2}") String defaultTtsModel,
            @Value("${spring.ai.elevenlabs.tts.output-format:mp3_44100_128}") String defaultOutputFormat) {
        this.textToSpeechModel = textToSpeechModel;
        this.voicesApiHolder = voicesApiHolder;
        this.transcriptionService = transcriptionService;
        this.voiceChatService = voiceChatService;
        this.jsonMapper = jsonMapper;
        this.defaultTtsModel = defaultTtsModel;
        this.defaultOutputFormat = defaultOutputFormat;
    }

    @Operation(summary = "文本转语音", description = "ElevenLabs TTS，返回 MP3 音频流")
    @PostMapping(value = "/tts/speak", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> speak(@RequestBody TextToSpeechRequest request) {
        requireElevenLabsKey();

        double stability = request.stability() != null ? request.stability() : 0.75;
        double similarityBoost = request.similarityBoost() != null ? request.similarityBoost() : 0.75;
        double style = request.style() != null ? request.style() : 0.3;
        boolean useSpeakerBoost = request.useSpeakerBoost() == null || request.useSpeakerBoost();
        double speed = request.speed() != null ? request.speed() : 1.0;

        var voiceSettings = new ElevenLabsApi.SpeechRequest.VoiceSettings(
                stability, similarityBoost, style, useSpeakerBoost, speed);

        var options = ElevenLabsTextToSpeechOptions.builder()
                .model(defaultTtsModel)
                .voiceId(request.voiceId())
                .voiceSettings(voiceSettings)
                .outputFormat(defaultOutputFormat)
                .build();

        TextToSpeechResponse response = textToSpeechModel.call(new TextToSpeechPrompt(request.text(), options));
        byte[] audio = response.getResult().getOutput();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/mpeg"));
        headers.setContentLength(audio.length);
        return new ResponseEntity<>(audio, headers, HttpStatus.OK);
    }

    @Operation(summary = "列出所有语音")
    @GetMapping("/voices")
    public ResponseEntity<?> getAllVoices() {
        requireElevenLabsKey();
        return voicesApiHolder.getVoicesApi().getVoices();
    }

    @Operation(summary = "默认语音设置")
    @GetMapping("/voices/settings/default")
    public ResponseEntity<?> getDefaultVoiceSettings() {
        requireElevenLabsKey();
        return voicesApiHolder.getVoicesApi().getDefaultVoiceSettings();
    }

    @Operation(summary = "语音详情")
    @GetMapping("/voices/{voiceId}")
    public ResponseEntity<?> getVoiceDetail(@PathVariable String voiceId) {
        requireElevenLabsKey();
        return voicesApiHolder.getVoicesApi().getVoice(voiceId);
    }

    @Operation(summary = "语音推荐设置")
    @GetMapping("/voices/{voiceId}/settings")
    public ResponseEntity<?> getVoiceSettings(@PathVariable String voiceId) {
        requireElevenLabsKey();
        return voicesApiHolder.getVoicesApi().getVoiceSettings(voiceId);
    }

    @Operation(summary = "语音转文字", description = "上传录音，ElevenLabs Scribe 转写")
    @PostMapping(value = "/stt/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "languageCode", required = false) String languageCode) {
        if (!transcriptionService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "请设置环境变量 ELEVENLABS_API_KEY 并重启应用"));
        }
        try {
            SttTranscribeResponse result = transcriptionService.transcribe(file, languageCode);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取音频失败: " + ex.getMessage());
        }
    }

    @Operation(summary = "语音对话流式", description = "SSE：TOKEN + AUDIO_CHUNK")
    @PostMapping(value = "/voice-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter voiceChatStream(@RequestBody VoiceChatRequest request) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        if (!StringUtils.hasText(request.getMessage())) {
            sendFailedAndComplete(emitter, "message 不能为空");
            return emitter;
        }
        if (!transcriptionService.isConfigured()) {
            sendFailedAndComplete(emitter, "请设置环境变量 ELEVENLABS_API_KEY 并重启应用");
            return emitter;
        }
        boolean autoSpeak = request.getAutoSpeak() == null || request.getAutoSpeak();
        voiceChatService.streamChat(request.getMessage(), request.getVoiceId(), autoSpeak, emitter, jsonMapper);
        return emitter;
    }

    private void requireElevenLabsKey() {
        if (!transcriptionService.isConfigured()) {
            throw new IllegalStateException("请设置环境变量 ELEVENLABS_API_KEY 并重启应用");
        }
    }

    private void sendFailedAndComplete(SseEmitter emitter, String error) {
        try {
            String json = jsonMapper.writeValueAsString(VoiceChatSseEvent.failed(error));
            emitter.send(SseEmitter.event().data(json).build());
            emitter.complete();
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }
}
