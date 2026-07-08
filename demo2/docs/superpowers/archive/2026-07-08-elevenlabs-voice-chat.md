# ElevenLabs 语音对话 · 功能归档

**归档日期**: 2026-07-08  
**项目**: spring-ai-demo / demo2  
**状态**: 初版已实现，可联调演示

---

## 1. 功能概述

在 demo2 首页新增 **「🎙️ 语音对话（ElevenLabs）」** 独立 Tab，落地微信教程第十四篇 ElevenLabs TTS，并扩展为完整语音对话：

1. **语音输入**：按住录音 → 松手上传 → ElevenLabs Scribe v2 转文字 → 自动发起对话
2. **流式对话**：DeepSeek `ChatClient` SSE 流式返回 Markdown
3. **流式朗读**：按句分段 ElevenLabs TTS，前端音频队列边收边播

与现有「💬 AI 聊天」Tab **完全独立**，不共享会话记忆。

**原文**: [Spring AI 2.0 系列教程（十四）——用 ElevenLabs 打造高质量语音](https://mp.weixin.qq.com/s/wW900pqfY3uArU0guyj1pg)

---

## 2. 架构（当前实现）

```
用户（浏览器 Tab）
    │ 按住录音 → POST /api/stt/transcribe (multipart webm)
    ▼
ElevenLabsTranscriptionService → Scribe v2
    │ 识别文本
    ▼ POST /api/voice-chat/stream
VoiceChatService
    │ ChatClient.stream() → DeepSeek
    │ SentenceBuffer 分句 → ElevenLabsTextToSpeechModel
    ▼
SSE: RUNNING | USER_TEXT | TOKEN | AUDIO_CHUNK | COMPLETED | FAILED
    ▼
前端 Markdown 渲染 + Audio 队列播放
```

**文章保留 API**（Swagger/curl 测试）：

- `POST /api/tts/speak` — 文本转 MP3
- `GET /api/voices` 等 4 个 Voices 端点

---

## 3. 关键设计决策（与 spec 差异）

| 维度 | spec 设想 | 当前实现 |
|------|----------|----------|
| Controller | 4 个独立 Controller | 合并为 **`VoiceApiController`**（`/api/*`） |
| 默认音色 | Rachel (`21m00Tcm4TlvDq8ikWAM`) | **Roger** (`CwhRBWXzGAHq8TQ4Fs17`)，免费套餐 API 402 |
| HTTP 客户端 | 默认 RestClient | **`ElevenLabsHttpClientConfig`** + STT 专用 `SimpleClientHttpRequestFactory`，读超时 120s，避免 Netty `ReadTimeoutException` |
| 双 TTS Bean | `@Qualifier` | `spring.ai.model.audio.speech=elevenlabs` + `@Qualifier("elevenLabsSpeechModel")` |
| STT | Spring AI Starter | **自封装 RestClient**，Spring AI 暂无 ElevenLabs STT Starter |

---

## 4. 文件清单

### 后端

| 文件 | 职责 |
|------|------|
| `config/ElevenLabsVoiceConfig.java` | 启用 `ElevenLabsVoiceProperties` |
| `config/ElevenLabsVoiceProperties.java` | STT/TTS 参数（model、language、分句长度） |
| `config/ElevenLabsVoicesApiHolder.java` | Voices API Bean |
| `config/ElevenLabsHttpClientConfig.java` | 全局 RestClient 改用 JDK HTTP，读超时 120s |
| `controller/VoiceApiController.java` | TTS / Voices / STT / 语音对话 SSE |
| `model/TextToSpeechRequest.java` | TTS 请求 DTO |
| `model/SttTranscribeResponse.java` | STT 响应 `{ text, language }` |
| `model/VoiceChatRequest.java` | `{ message, voiceId?, autoSpeak? }` |
| `model/VoiceChatSseEvent.java` | SSE 事件 DTO |
| `service/ElevenLabsTranscriptionService.java` | RestClient → `POST /v1/speech-to-text` |
| `service/VoiceChatService.java` | 流式对话 + 分句 TTS |

### 前端

| 文件 | 职责 |
|------|------|
| `static/index.html` | Tab 入口与 UI 结构 |
| `static/js/tabs/voice-chat.js` | 录音、STT、SSE、音频队列 |
| `static/css/tabs/voice-chat.css` | 麦克风按钮、状态栏样式 |

### 测试

| 文件 | 覆盖 |
|------|------|
| `test/.../ElevenLabsTranscriptionServiceTest.java` | API Key / 空文件校验 |
| `test/.../VoiceChatServiceTest.java` | 分句缓冲算法 |

---

## 5. 配置与启动

### 必需环境变量

```powershell
# LLM（已有）
$env:DEEPSEEK_API_KEY = "..."

# ElevenLabs TTS + STT 共用
$env:ELEVENLABS_API_KEY = "..."
```

Key 获取：[ElevenLabs API Keys](https://elevenlabs.io/app/developers/api-keys)

### application.properties 要点

```properties
spring.ai.model.audio.speech=elevenlabs
spring.ai.elevenlabs.api-key=${ELEVENLABS_API_KEY:}
spring.ai.elevenlabs.tts.model-id=eleven_multilingual_v2
spring.ai.elevenlabs.tts.output-format=mp3_44100_128
spring.ai.elevenlabs.tts.voice-id=CwhRBWXzGAHq8TQ4Fs17

agent.voice-chat.stt.model-id=scribe_v2
agent.voice-chat.stt.language-code=zh
agent.voice-chat.tts.sentence-max-chars=40
```

### 启动与访问

```powershell
cd demo2
.\mvnw.cmd spring-boot:run
# 打开 http://localhost:8081 → 「🎙️ 语音对话（ElevenLabs）」Tab
```

未配置 `ELEVENLABS_API_KEY` 时应用仍可启动；语音相关 API 返回 `503` 或 SSE `FAILED` 明确提示。

---

## 6. API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/stt/transcribe` | multipart 上传录音，Scribe 转写 |
| `POST` | `/api/voice-chat/stream` | SSE 流式对话 + 分句 TTS |
| `POST` | `/api/tts/speak` | 文本转 MP3（文章 curl 场景） |
| `GET` | `/api/voices` | 所有可用语音 |
| `GET` | `/api/voices/settings/default` | 默认语音设置 |
| `GET` | `/api/voices/{voiceId}` | 语音详情 |
| `GET` | `/api/voices/{voiceId}/settings` | 语音推荐参数 |

**SSE 事件**：`RUNNING` · `USER_TEXT` · `TOKEN` · `AUDIO_CHUNK` · `COMPLETED` · `FAILED`

**STT 错误码**：`400` 空文件；`502` ElevenLabs 失败/网络超时；`503` 未配置 API Key

---

## 7. 典型使用流程

1. 打开 Tab → 页面加载 `GET /api/voices` 填充音色下拉
2. **按住 🎤** 说话 → 松手自动 `POST /api/stt/transcribe`
3. 识别文本显示为用户气泡 → 自动 `POST /api/voice-chat/stream`
4. 助手回复流式 Markdown；勾选「自动朗读」时 `AUDIO_CHUNK` 顺序播放
5. 也可直接输入文字发送（跳过 STT）

---

## 8. 已知限制与排错

| 项 | 说明 |
|----|------|
| 网络超时 | 日志出现 `reactor.netty...ReadTimeoutException` 多为旧版 Netty 客户端；已改用 JDK HTTP + 120s 读超时，**需重启应用** |
| 录音过短 | 前端 `< 1KB` 提示「录音太短」 |
| 免费音色 | Rachel 等 library voice 可能 HTTP 402，已改默认 Roger |
| 无会话记忆 | 首版单次问答，不挂 ChatMemory |
| Realtime STT | 未实现 Scribe v2 Realtime WebSocket（方案 B） |

---

## 9. 文档索引

| 类型 | 路径 |
|------|------|
| Spec | `docs/superpowers/specs/2026-07-07-elevenlabs-voice-chat-design.md` |
| Plan | `docs/superpowers/plans/2026-07-07-elevenlabs-voice-chat.md` |
| 归档 | `docs/superpowers/archive/2026-07-08-elevenlabs-voice-chat.md`（本文） |

---

## 10. 参考链接

- [Spring AI ElevenLabs TTS 文档](https://docs.spring.io/spring-ai/reference/api/audio/speech/elevenlabs-speech.html)
- [ElevenLabs STT API](https://elevenlabs.io/zh/speech-to-text-api)
- [ElevenLabs Create transcript API](https://elevenlabs.io/docs/api-reference/speech-to-text/convert)
