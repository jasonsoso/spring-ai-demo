# ElevenLabs 语音对话 Demo Implementation Plan

> **Status:** ✅ 已完成（2026-07-07）— `mvn compile` / 单元测试通过；后续补丁：STT/TTS HTTP 超时与异常处理（`ElevenLabsHttpClientConfig`）。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在 demo2 新增独立 Tab「🎙️ 语音对话（ElevenLabs）」：按住录音 → Scribe STT → DeepSeek 流式对话 → 分句 ElevenLabs TTS 边播；保留文章 TTS/Voices API。

**Architecture:** TTS 走 `spring-ai-starter-model-elevenlabs` + `@Qualifier("elevenLabsSpeechModel")`；STT 自封装 `ElevenLabsTranscriptionService`（RestClient → `POST /v1/speech-to-text`）；`VoiceChatService` 编排 ChatClient 流式 + 分句 TTS → SSE；前端 `MediaRecorder` + 音频队列播放。控制器合并为 `VoiceApiController`（`/api/*`）。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0, DeepSeek ChatClient, ElevenLabs TTS/STT, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-07-elevenlabs-voice-chat-design.md](../specs/2026-07-07-elevenlabs-voice-chat-design.md)

**原文:** [Spring AI 2.0 系列教程（十四）——用 ElevenLabs 打造高质量语音](https://mp.weixin.qq.com/s/wW900pqfY3uArU0guyj1pg)

## Global Constraints

- **TTS**：`spring-ai-starter-model-elevenlabs`；`spring.ai.model.audio.speech=elevenlabs`
- **STT**：ElevenLabs Scribe v2，**非** Spring AI Starter；共用 `ELEVENLABS_API_KEY`
- **语音输入**：方案 A — 按住录音、松手上传识别（非 Realtime WebSocket）
- **独立 Tab**：不改现有「💬 AI 聊天」
- **默认音色**：`CwhRBWXzGAHq8TQ4Fs17`（Roger，免费套餐可用）
- **分句阈值**：`agent.voice-chat.tts.sentence-max-chars=40`
- **无会话记忆**：首版单次问答
- **未配置 Key**：应用可启动；语音 API 返回 `503`/`FAILED` 明确提示

---

## File Structure

| 文件 | 职责 |
|------|------|
| `pom.xml` | 新增 `spring-ai-starter-model-elevenlabs` |
| `application.properties` | ElevenLabs TTS/STT 配置 |
| `config/ElevenLabsVoiceConfig.java` | 启用 `ElevenLabsVoiceProperties` |
| `config/ElevenLabsVoiceProperties.java` | STT/TTS 参数（model、language、分句长度） |
| `config/ElevenLabsVoicesApiHolder.java` | Voices API Bean |
| `config/ElevenLabsHttpClientConfig.java` | RestClient 改用 JDK HTTP，读超时 120s（补丁） |
| `controller/VoiceApiController.java` | `/api/tts/speak`、`/api/voices/*`、`/api/stt/transcribe`、`/api/voice-chat/stream` |
| `model/TextToSpeechRequest.java` | TTS 请求 DTO（文章 Record） |
| `model/SttTranscribeResponse.java` | STT 响应 `{ text, language }` |
| `model/VoiceChatRequest.java` | `{ message, voiceId?, autoSpeak? }` |
| `model/VoiceChatSseEvent.java` | SSE 事件 DTO |
| `service/ElevenLabsTranscriptionService.java` | RestClient 调 Scribe v2 |
| `service/VoiceChatService.java` | 流式对话 + 分句 TTS |
| `test/.../ElevenLabsTranscriptionServiceTest.java` | API Key / 空文件校验 |
| `test/.../VoiceChatServiceTest.java` | 分句缓冲算法 |
| `static/js/tabs/voice-chat.js` | 录音、STT、SSE、音频队列 |
| `static/css/tabs/voice-chat.css` | 麦克风、状态栏样式 |
| `static/index.html` | 注册新 Tab |

---

### Task 1: Maven 依赖与配置

**Files:**
- Modify: `demo2/pom.xml`
- Modify: `demo2/src/main/resources/application.properties`

- [x] **Step 1:** `pom.xml` 添加 `spring-ai-starter-model-elevenlabs`
- [x] **Step 2:** `application.properties` 添加 ElevenLabs TTS/STT 配置项
- [x] **Step 3:** `mvn compile` 验证通过

---

### Task 2: 配置与 Model

**Files:**
- Create: `ElevenLabsVoiceConfig.java`, `ElevenLabsVoiceProperties.java`, `ElevenLabsVoicesApiHolder.java`
- Create: `TextToSpeechRequest.java`, `SttTranscribeResponse.java`, `VoiceChatRequest.java`, `VoiceChatSseEvent.java`

- [x] **Step 1:** 配置属性类（STT model、language、分句长度）
- [x] **Step 2:** Voices API Holder Bean
- [x] **Step 3:** 四个 model DTO

---

### Task 3: STT 服务

**Files:**
- Create: `ElevenLabsTranscriptionService.java`

- [x] **Step 1:** RestClient multipart 上传音频 → `POST /v1/speech-to-text`
- [x] **Step 2:** 解析 `text` / `transcripts[0].text`
- [x] **Step 3:** 空文件 `IllegalArgumentException`；API 失败 `IllegalStateException`
- [x] **Step 4（补丁）:** `SimpleClientHttpRequestFactory` 读超时 120s；捕获 `ResourceAccessException`

---

### Task 4: 流式对话 + 分句 TTS

**Files:**
- Create: `VoiceChatService.java`

- [x] **Step 1:** `ChatClient.stream()` → 推送 `TOKEN` SSE
- [x] **Step 2:** `SentenceBuffer` 按标点或 ≥40 字分句
- [x] **Step 3:** `@Qualifier("elevenLabsSpeechModel")` 调用 TTS → `AUDIO_CHUNK`
- [x] **Step 4:** 单句 TTS 失败 WARN 跳过，不中断文字流

---

### Task 5: REST 控制器

**Files:**
- Create: `VoiceApiController.java`

- [x] **Step 1:** `POST /api/tts/speak`（文章）
- [x] **Step 2:** `GET /api/voices` 等 4 个端点
- [x] **Step 3:** `POST /api/stt/transcribe`（multipart）
- [x] **Step 4:** `POST /api/voice-chat/stream`（SSE）
- [x] **Step 5（补丁）:** STT 异常映射 `400`/`502`；未配置 Key 返回 `503` + JSON `error`

---

### Task 6: 前端 Tab

**Files:**
- Create: `voice-chat.js`, `voice-chat.css`
- Modify: `index.html`

- [x] **Step 1:** Tab 注册 + 聊天气泡 UI
- [x] **Step 2:** `MediaRecorder` 按住录音 / 松手上传（`recorder.start(200)` 分片）
- [x] **Step 3:** STT → 自动 `voice-chat/stream`
- [x] **Step 4:** `AUDIO_CHUNK` 队列顺序播放
- [x] **Step 5:** 音色下拉 `GET /api/voices`；文字输入备用
- [x] **Step 6（补丁）:** 解析后端错误 JSON，显示具体信息

---

### Task 7: HTTP 客户端超时（补丁）

**Files:**
- Create: `ElevenLabsHttpClientConfig.java`

- [x] **Step 1:** 全局 `RestClient.Builder` 改用 JDK HTTP，避免 Reactor Netty `ReadTimeoutException`
- [x] **Step 2:** 连接超时 30s，读超时 120s

---

### Task 8: 测试与验证

- [x] **Step 1:** `ElevenLabsTranscriptionServiceTest` — API Key / 空文件
- [x] **Step 2:** `VoiceChatServiceTest` — 分句缓冲算法
- [x] **Step 3:** `mvn compile test` 通过
- [x] **Step 4:** 手工冒烟 — 文字对话、音色列表、STT、流式朗读

---

## 实现偏差说明（相对 spec）

| spec 设想 | 实际实现 | 原因 |
|-----------|----------|------|
| 4 个独立 Controller | 合并为 `VoiceApiController` | 减少样板代码，Swagger 分组不变 |
| 默认音色 Rachel | 改为 Roger (`CwhRBWXzGAHq8TQ4Fs17`) | 免费套餐 API 402 |
| 默认 RestClient | 增加 `ElevenLabsHttpClientConfig` | webflux 环境下 Netty 读超时导致 STT 500 |

---

## 运行方式

```powershell
$env:ELEVENLABS_API_KEY = [Environment]::GetEnvironmentVariable('ELEVENLABS_API_KEY','User')
$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')
cd d:\ai\spring-ai-demo\demo2
.\mvnw.cmd spring-boot:run
```

打开 `http://localhost:8081` → **🎙️ 语音对话（ElevenLabs）**

**归档**: [docs/superpowers/archive/2026-07-08-elevenlabs-voice-chat.md](../archive/2026-07-08-elevenlabs-voice-chat.md)
