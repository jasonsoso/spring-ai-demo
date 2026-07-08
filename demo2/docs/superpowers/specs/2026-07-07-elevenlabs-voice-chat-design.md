# ElevenLabs 语音对话 Demo 设计规范

**日期**: 2026-07-07  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现  
**实现计划**: [docs/superpowers/plans/2026-07-07-elevenlabs-voice-chat.md](../plans/2026-07-07-elevenlabs-voice-chat.md)  
**原文**: [Spring AI 2.0 系列教程（十四）——用 ElevenLabs 打造高质量语音](https://mp.weixin.qq.com/s/wW900pqfY3uArU0guyj1pg)  
**ElevenLabs STT**: [转录（STT）API](https://elevenlabs.io/zh/speech-to-text-api) · [Create transcript API](https://elevenlabs.io/docs/api-reference/speech-to-text/convert)

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中落地微信教程第十四篇的 **ElevenLabs TTS** 能力，并扩展为完整语音对话 Demo：

1. **语音输入**：用户按住录音、松手上传，后端调用 ElevenLabs Scribe 转文字后自动发起对话。
2. **流式对话**：复用 DeepSeek `ChatClient` SSE 流式返回文本。
3. **流式朗读**：LLM 流式输出过程中按句分段调用 ElevenLabs TTS，前端边收边播。

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| TTS 提供商 | **ElevenLabs**（`spring-ai-starter-model-elevenlabs`，文章方案） |
| STT 提供商 | **ElevenLabs Scribe v2**（同一 `ELEVENLABS_API_KEY`，自封装 RestClient，非 Spring AI 官方 Starter） |
| 语音输入模式 | **A：录完再识别**（MediaRecorder → multipart 上传 → 转写 → 发送） |
| 交互入口 | **新建独立 Tab**「🎙️ 语音对话（ElevenLabs）」，不改动现有「💬 AI 聊天」 |
| LLM | **DeepSeek**（复用现有 `ChatClient.Builder`） |
| TTS 流式策略 | LLM token 缓冲至句末标点或 ≥40 字 → 分段 TTS → SSE `AUDIO_CHUNK` |
| 文章 API | 保留 `POST /api/tts/speak`、`GET /api/voices` 等端点供 Swagger/curl 测试 |

### 1.3 技术约束说明

- **ElevenLabs 平台**支持 STT（Scribe v2 / Scribe v2 Realtime），但 **Spring AI 2.0 的 `spring-ai-starter-model-elevenlabs` 仅封装 TTS**（`ElevenLabsTextToSpeechModel`），不包含 `TranscriptionModel` 自动配置。
- STT 通过 `ElevenLabsTranscriptionService` 直接调用 `POST https://api.elevenlabs.io/v1/speech-to-text`，与 TTS 共用 API Key，保持「一家厂商」体验。
- `demo2` 已有 `spring-ai-starter-model-openai`，可能与 ElevenLabs 产生双 `TextToSpeechModel` Bean；通过 `spring.ai.model.audio.speech=elevenlabs` + `@Qualifier("elevenLabsSpeechModel")` 解决。

### 1.4 依赖

| 依赖 | 说明 |
|------|------|
| `spring-ai-starter-model-elevenlabs` | **新增**，TTS 自动配置 |
| `spring-boot-starter-web` | 已有 |
| `spring-boot-starter-webflux` | 已有，可选用于 RestClient/WebClient |
| ElevenLabs Java SDK | **不引入**，STT 用 RestClient 调 REST，减少依赖 |

### 1.5 成功标准

1. `mvnw.cmd -pl demo2 compile` 编译通过。
2. 配置 `ELEVENLABS_API_KEY` 后，语音 Tab 可：录音 → 识别中文 → 流式文字回复 → 自动朗读。
3. `GET /api/voices` 返回语音列表，前端可切换 `voiceId`。
4. `POST /api/tts/speak` 可独立测试 TTS（文章 curl 场景）。
5. 未配置 Key 时应用仍可启动，语音相关请求返回明确 `FAILED` 提示。

### 1.6 不在范围

- Scribe v2 Realtime WebSocket 边说边识别（方案 B）
- 浏览器 Web Speech API 前端识别
- OpenAI Whisper 混合方案
- 多轮会话记忆（首版单次问答，无 ChatMemory）
- 语音克隆、自定义 Voice Design

---

## 2. 架构设计

### 2.1 数据流

```
用户按住录音 → MediaRecorder → 松手
    → POST /api/stt/transcribe (multipart)
    → ElevenLabsTranscriptionService → Scribe v2 → 文本
    → POST /api/voice-chat/stream { message, voiceId?, autoSpeak? }
    → VoiceChatService
        → ChatClient.stream() → DeepSeek
        → 分句缓冲 → ElevenLabsTextToSpeechModel.call()
    → SSE: RUNNING | USER_TEXT | TOKEN | AUDIO_CHUNK | COMPLETED | FAILED
    → 前端 Markdown 渲染 + Audio 队列播放
```

### 2.2 组件职责

| 类 | 包 | 职责 |
|----|-----|------|
| `ElevenLabsVoiceConfig` | `config` | 文档化 Bean 注入约定；可选 `@ConditionalOnProperty` 健康检查 |
| `TextToSpeechController` | `controller` | 文章 `POST /api/tts/speak` |
| `VoicesController` | `controller` | 文章 4 个 `/api/voices` 端点 |
| `SttController` | `controller` | `POST /api/stt/transcribe` |
| `VoiceChatController` | `controller` | `POST /api/voice-chat/stream` SSE |
| `TextToSpeechRequest` | `model` | 文章 Record DTO |
| `SttTranscribeResponse` | `model` | `{ text, language? }` |
| `VoiceChatRequest` | `model` | `{ message, voiceId?, autoSpeak? }` |
| `VoiceChatSseEvent` | `model` | SSE 事件 DTO |
| `ElevenLabsTranscriptionService` | `service` | RestClient 调 ElevenLabs STT API |
| `VoiceChatService` | `service` | 流式对话 + 分句 TTS 编排 |

### 2.3 与现有模块关系

- **独立 Tab**：不修改 `ChatController`、`chat.js`。
- **复用**：`ChatClient.Builder`（DeepSeek）、`JsonMapper`、`components.css` 气泡样式、`markdown.js` 流式渲染。
- **隔离**：无 MySQL / Milvus / MCP 依赖；仅依赖 DeepSeek + ElevenLabs API。

---

## 3. API 设计

### 3.1 STT — `POST /api/stt/transcribe`

**请求**: `multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | File | ✅ | 录音文件，webm/mp4/wav，建议 ≤25MB |
| `languageCode` | String | ❌ | 默认 `zh`，传给 ElevenLabs |

**响应** `200`:

```json
{ "text": "你好，请介绍一下 Spring AI", "language": "zh" }
```

**错误**: `400` 空文件；`502` ElevenLabs 调用失败；`503` 未配置 API Key。

### 3.2 语音对话 SSE — `POST /api/voice-chat/stream`

**请求** `application/json`:

```json
{
  "message": "你好，请介绍一下 Spring AI",
  "voiceId": "21m00Tcm4TlvDq8ikWAM",
  "autoSpeak": true
}
```

| 字段 | 默认 | 说明 |
|------|------|------|
| `message` | — | 用户消息（通常来自 STT 结果） |
| `voiceId` | 配置默认 | ElevenLabs 语音 ID |
| `autoSpeak` | `true` | `false` 时只推 `TOKEN`，不推 `AUDIO_CHUNK` |

**SSE 事件**（`data` 为 JSON）:

| type | 字段 | 说明 |
|------|------|------|
| `RUNNING` | — | 本轮开始 |
| `USER_TEXT` | `text` | 回显用户消息（可选，便于前端统一渲染） |
| `TOKEN` | `response` | LLM 文本片段 |
| `AUDIO_CHUNK` | `audioBase64`, `mimeType` | 一句 TTS MP3（`audio/mpeg`） |
| `COMPLETED` | — | 本轮结束 |
| `FAILED` | `error` | 错误描述 |

### 3.3 TTS — `POST /api/tts/speak`（文章）

与教程一致：`TextToSpeechRequest` Record → `byte[]` MP3，`Content-Type: audio/mpeg`。

### 3.4 Voices — `GET /api/voices`（文章）

| 端点 | 说明 |
|------|------|
| `GET /api/voices` | 所有可用语音 |
| `GET /api/voices/settings/default` | 默认语音设置 |
| `GET /api/voices/{voiceId}` | 语音详情 |
| `GET /api/voices/{voiceId}/settings` | 语音推荐参数 |

---

## 4. 核心实现细节

### 4.1 ElevenLabs STT 调用

```
POST https://api.elevenlabs.io/v1/speech-to-text
Header: xi-api-key: ${ELEVENLABS_API_KEY}
Body: multipart
  - file: 音频二进制
  - model_id: scribe_v2
  - language_code: zh（可配置）
响应 JSON: 取 text 字段（或 transcripts[0].text，按实际 API 响应适配）
```

实现类 `ElevenLabsTranscriptionService` 使用 Spring 6 `RestClient`，构造时注入 `spring.ai.elevenlabs.api-key`。

### 4.2 分句 TTS 缓冲算法

`VoiceChatService` 维护 `StringBuilder buffer`：

1. 每收到 LLM token，追加到 buffer，同时推送 `TOKEN` SSE。
2. 当 buffer 匹配句末 `[。！？；\n]` 或 `buffer.length() >= 40`：
   - 取出句子 `sentence`
   - 若 `autoSpeak == true`，调用 `textToSpeechModel.call(new TextToSpeechPrompt(sentence, options))`
   - 推送 `AUDIO_CHUNK`（Base64 编码 MP3）
   - 清空 buffer 中已处理部分
3. 流结束（`onComplete`）时冲刷 buffer 剩余文本。

TTS 选项（与文章一致）：

```java
ElevenLabsTextToSpeechOptions.builder()
    .model("eleven_multilingual_v2")
    .voiceId(voiceId)
    .outputFormat("mp3_44100_128")
    .build();
```

单句 TTS 失败：记录 `WARN` 日志，跳过该句，不中断文字流。

### 4.3 Bean 注入

```java
public VoiceChatService(
    ChatClient.Builder chatClientBuilder,
    @Qualifier("elevenLabsSpeechModel") TextToSpeechModel textToSpeechModel,
    ElevenLabsTranscriptionService transcriptionService) { ... }
```

`TextToSpeechController` 可注入 `ElevenLabsTextToSpeechModel` 具体类型（与文章一致），或 `@Qualifier` + 接口。

---

## 5. 配置

### 5.1 application.properties 新增

```properties
# ===== ElevenLabs TTS + STT（共用 API Key）=====
spring.ai.model.audio.speech=elevenlabs
spring.ai.elevenlabs.api-key=${ELEVENLABS_API_KEY:}
spring.ai.elevenlabs.tts.model-id=eleven_multilingual_v2
spring.ai.elevenlabs.tts.output-format=mp3_44100_128
spring.ai.elevenlabs.tts.voice-id=21m00Tcm4TlvDq8ikWAM
spring.ai.elevenlabs.tts.enable-logging=true

# STT 自封装（非 Spring AI 自动配置）
agent.voice-chat.stt.model-id=scribe_v2
agent.voice-chat.stt.language-code=zh
agent.voice-chat.stt.enabled=true
agent.voice-chat.tts.sentence-max-chars=40
```

### 5.2 环境变量

| 变量 | 必需 | 说明 |
|------|------|------|
| `ELEVENLABS_API_KEY` | 语音 Tab 需要 | [ElevenLabs API Keys](https://elevenlabs.io/app/developers/api-keys) |
| DeepSeek API Key | 已有 | LLM 对话 |

---

## 6. 前端设计

### 6.1 新增文件

| 文件 | 说明 |
|------|------|
| `css/tabs/voice-chat.css` | 麦克风按钮、录音状态、音色选择器 |
| `js/tabs/voice-chat.js` | 录音、STT、SSE、音频队列 |
| `index.html` | Tab 按钮 + 面板 HTML |

### 6.2 交互

1. **音色下拉**：页面加载时 `GET /api/voices`，填充 `voiceId` 选择器；失败时使用配置默认 ID。
2. **录音按钮**：`mousedown`/`touchstart` 开始录音，`mouseup`/`touchend` 停止；录音中显示红色脉冲动画。
3. **识别**：松手后 `POST /api/stt/transcribe`，展示识别文本为用户气泡。
4. **对话**：自动 `POST /api/voice-chat/stream`，流式渲染助手 Markdown。
5. **朗读**：`AUDIO_CHUNK` 入队，`HTMLAudioElement` 顺序播放；「自动朗读」开关控制 `autoSpeak`。
6. **文字输入**：保留输入框 + 发送按钮，支持纯文字发起（跳过 STT）。

### 6.3 音频格式

- 录音：`MediaRecorder`，优先 `audio/webm;codecs=opus`，Safari 降级 `audio/mp4`。
- 播放：ElevenLabs 返回 MP3，`mimeType: audio/mpeg`。

---

## 7. 错误处理

| 场景 | 行为 |
|------|------|
| `ELEVENLABS_API_KEY` 未设置 | `SttController` / `VoiceChatController` 返回 `503` 或 SSE `FAILED` |
| 麦克风权限拒绝 | 前端 alert「请允许麦克风权限」 |
| 空录音 / 过短 | 前端提示「录音太短」 |
| ElevenLabs STT 4xx/5xx | `502` + 错误信息 |
| DeepSeek 流式失败 | SSE `FAILED` |
| 单句 TTS 失败 | 跳过，文字继续 |
| 双 TTS Bean | `@Qualifier` + `spring.ai.model.audio.speech=elevenlabs` |

---

## 8. 测试计划

| 类型 | 内容 |
|------|------|
| 编译 | `mvnw.cmd -pl demo2 compile` |
| 单元测试 | `ElevenLabsTranscriptionServiceTest`：Mock RestClient 验证 multipart 请求与 JSON 解析 |
| 单元测试 | `VoiceChatServiceTest`：Mock ChatClient + TTS，验证分句逻辑 |
| 冒烟 | 启动后 `GET /`、`/js/tabs/voice-chat.js`、`/css/tabs/voice-chat.css` 返回 200 |
| 手工 | 录音中文 → 识别 → 流式回复 → 朗读；切换音色；关闭自动朗读；curl `/api/tts/speak` |

---

## 9. 文件清单

```
demo2/
├── pom.xml
├── src/main/java/com/jason/demo/demo2/
│   ├── config/ElevenLabsVoiceConfig.java
│   ├── controller/
│   │   ├── TextToSpeechController.java
│   │   ├── VoicesController.java
│   │   ├── SttController.java
│   │   └── VoiceChatController.java
│   ├── model/
│   │   ├── TextToSpeechRequest.java
│   │   ├── SttTranscribeResponse.java
│   │   ├── VoiceChatRequest.java
│   │   └── VoiceChatSseEvent.java
│   └── service/
│       ├── ElevenLabsTranscriptionService.java
│       └── VoiceChatService.java
├── src/main/resources/
│   ├── application.properties
│   └── static/
│       ├── index.html
│       ├── css/tabs/voice-chat.css
│       └── js/tabs/voice-chat.js
├── src/test/java/.../
│   ├── ElevenLabsTranscriptionServiceTest.java
│   └── VoiceChatServiceTest.java
├── docs/superpowers/
│   ├── specs/2026-07-07-elevenlabs-voice-chat-design.md
│   └── plans/2026-07-07-elevenlabs-voice-chat.md
```

---

## 10. 参考

- [Spring AI ElevenLabs TTS 文档](https://docs.spring.io/spring-ai/reference/api/audio/speech/elevenlabs-speech.html)
- [Spring AI TranscriptionModel 接口](https://docs.spring.io/spring-ai/reference/api/audio/transcriptions.html)
- [ElevenLabs STT 产品页](https://elevenlabs.io/zh/speech-to-text-api)
- [ElevenLabs Create transcript API](https://elevenlabs.io/docs/api-reference/speech-to-text/convert)
