// ========== ElevenLabs 语音对话 ==========

let voiceMediaRecorder = null;
let voiceAudioStream = null;
let voiceAudioChunks = [];
let voiceIsRecording = false;
let voiceAudioQueue = [];
let voiceIsPlaying = false;
let voiceChatBusy = false;
let voiceDefaultVoiceId = 'CwhRBWXzGAHq8TQ4Fs17';

function scrollVoiceMessages() {
    const box = document.getElementById('voiceChatMessages');
    if (box) box.scrollTop = box.scrollHeight;
}

function removeVoiceWelcome() {
    const welcome = document.getElementById('voiceChatWelcome');
    if (welcome) welcome.remove();
}

function appendVoiceBubble(text, isUser) {
    removeVoiceWelcome();
    const box = document.getElementById('voiceChatMessages');
    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = isUser ? 'message-content' : 'message-content markdown-body';
    if (isUser) {
        content.textContent = text;
    } else {
        content.innerHTML = renderMarkdown(text);
    }
    div.appendChild(content);
    box.appendChild(div);
    scrollVoiceMessages();
    return content;
}

function setVoiceChatInputEnabled(enabled) {
    document.getElementById('voiceMessageInput').disabled = !enabled;
    document.getElementById('voiceSendButton').disabled = !enabled;
    document.getElementById('voiceMicBtn').disabled = !enabled;
}

function setVoiceStatus(text, active) {
    const el = document.getElementById('voiceStatus');
    el.textContent = text;
    el.className = active ? 'voice-status active' : 'voice-status';
}

async function loadVoiceList() {
    const select = document.getElementById('voiceIdSelect');
    try {
        const res = await fetch('/api/voices');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        const voices = data.voices || data.body?.voices || [];
        select.innerHTML = '';
        if (!voices.length) {
            select.innerHTML = '<option value="' + voiceDefaultVoiceId + '">默认 (Rachel)</option>';
            return;
        }
        voices.forEach(function (v) {
            const opt = document.createElement('option');
            opt.value = v.voice_id || v.voiceId;
            opt.textContent = (v.name || opt.value) + ' (' + opt.value.substring(0, 8) + '…)';
            select.appendChild(opt);
        });
    } catch (e) {
        select.innerHTML = '<option value="' + voiceDefaultVoiceId + '">默认 (需配置 API Key)</option>';
    }
}

function getSelectedVoiceId() {
    const select = document.getElementById('voiceIdSelect');
    return select.value || voiceDefaultVoiceId;
}

function isAutoSpeakEnabled() {
    return document.getElementById('voiceAutoSpeak').checked;
}

function enqueueAudioBase64(base64, mimeType) {
    voiceAudioQueue.push({ base64: base64, mimeType: mimeType || 'audio/mpeg' });
    playNextAudioChunk();
}

function playNextAudioChunk() {
    if (voiceIsPlaying || voiceAudioQueue.length === 0) return;
    voiceIsPlaying = true;
    const item = voiceAudioQueue.shift();
    const binary = atob(item.base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const blob = new Blob([bytes], { type: item.mimeType });
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    audio.onended = function () {
        URL.revokeObjectURL(url);
        voiceIsPlaying = false;
        playNextAudioChunk();
    };
    audio.onerror = function () {
        URL.revokeObjectURL(url);
        voiceIsPlaying = false;
        playNextAudioChunk();
    };
    audio.play().catch(function () {
        voiceIsPlaying = false;
        playNextAudioChunk();
    });
}

function parseVoiceApiError(bodyText, status) {
    if (!bodyText) return 'HTTP ' + status;
    try {
        const json = JSON.parse(bodyText);
        return json.error || json.message || bodyText;
    } catch (_) {
        return bodyText;
    }
}

async function transcribeAndSend(blob) {
    if (blob.size < 1000) {
        alert('录音太短，请按住说话稍久一些');
        return;
    }
    setVoiceStatus('识别中…', true);
    setVoiceChatInputEnabled(false);

    const ext = blob.type && blob.type.includes('mp4') ? 'm4a' : 'webm';
    const form = new FormData();
    form.append('file', blob, 'recording.' + ext);

    try {
        const res = await fetch('/api/stt/transcribe', { method: 'POST', body: form });
        const bodyText = await res.text();
        if (!res.ok) {
            throw new Error(parseVoiceApiError(bodyText, res.status));
        }
        const data = JSON.parse(bodyText);
        if (!data.text || !data.text.trim()) throw new Error('未识别到有效文字');
        await sendVoiceChatMessage(data.text.trim(), true);
    } catch (e) {
        alert('语音识别失败：' + e.message);
        setVoiceChatInputEnabled(true);
        setVoiceStatus('待机', false);
    }
}

async function sendVoiceChatMessage(message, fromVoice) {
    if (voiceChatBusy) return;
    voiceChatBusy = true;
    voiceAudioQueue = [];
    voiceIsPlaying = false;

    appendVoiceBubble(message, true);
    setVoiceChatInputEnabled(false);
    setVoiceStatus('思考中…', true);

    const assistantContent = appendVoiceBubble('', false);
    const stream = createMarkdownStreamRenderer(assistantContent, scrollVoiceMessages);

    try {
        const response = await fetch('/api/voice-chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: message,
                voiceId: getSelectedVoiceId(),
                autoSpeak: isAutoSpeakEnabled()
            })
        });

        if (!response.ok) throw new Error('网络请求失败');

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const parts = buffer.split('\n\n');
            buffer = parts.pop() || '';
            for (const part of parts) {
                const line = part.split('\n').find(function (l) { return l.startsWith('data:'); });
                if (!line) continue;
                try {
                    const jsonStr = line.replace(/^data:\s*/, '').trim();
                    const data = JSON.parse(jsonStr);
                    if (data.type === 'TOKEN' && data.response) {
                        stream.append(data.response);
                    } else if (data.type === 'AUDIO_CHUNK' && data.audioBase64) {
                        enqueueAudioBase64(data.audioBase64, data.mimeType);
                    } else if (data.type === 'FAILED') {
                        throw new Error(data.error || '对话失败');
                    }
                } catch (parseErr) {
                    if (parseErr.message && parseErr.message !== '对话失败') {
                        // ignore JSON parse errors on partial chunks
                    } else {
                        throw parseErr;
                    }
                }
            }
        }
        stream.flush();
        setVoiceStatus(fromVoice ? '待机' : '完成', false);
    } catch (e) {
        assistantContent.textContent = '抱歉，发生了错误：' + e.message;
        setVoiceStatus('错误', false);
    } finally {
        voiceChatBusy = false;
        setVoiceChatInputEnabled(true);
        document.getElementById('voiceMessageInput').focus();
    }
}

async function startVoiceRecording() {
    if (voiceIsRecording || voiceChatBusy) return;
    try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        voiceAudioStream = stream;
        const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
            ? 'audio/webm;codecs=opus'
            : (MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm'
            : (MediaRecorder.isTypeSupported('audio/mp4') ? 'audio/mp4' : ''));
        voiceAudioChunks = [];
        const recorder = mimeType
            ? new MediaRecorder(stream, { mimeType: mimeType })
            : new MediaRecorder(stream);
        const recordedMimeType = recorder.mimeType || mimeType || 'audio/webm';

        recorder.ondataavailable = function (e) {
            if (e.data && e.data.size > 0) voiceAudioChunks.push(e.data);
        };
        recorder.onstop = function () {
            if (voiceAudioStream) {
                voiceAudioStream.getTracks().forEach(function (t) { t.stop(); });
                voiceAudioStream = null;
            }
            window.removeEventListener('mouseup', stopVoiceRecording);
            window.removeEventListener('pointerup', stopVoiceRecording);
            window.removeEventListener('pointercancel', stopVoiceRecording);

            const blob = new Blob(voiceAudioChunks, { type: recordedMimeType });
            voiceAudioChunks = [];
            transcribeAndSend(blob);
        };
        recorder.onerror = function () {
            setVoiceStatus('录音失败', false);
            stopVoiceRecording();
        };

        voiceMediaRecorder = recorder;
        // 分片采集，避免短按松手时没有 audio chunk
        recorder.start(200);
        voiceIsRecording = true;
        document.getElementById('voiceMicBtn').classList.add('recording');
        setVoiceStatus('录音中…', true);

        window.addEventListener('mouseup', stopVoiceRecording);
        window.addEventListener('pointerup', stopVoiceRecording);
        window.addEventListener('pointercancel', stopVoiceRecording);
    } catch (e) {
        alert('无法访问麦克风：' + e.message);
        setVoiceStatus('待机', false);
    }
}

function stopVoiceRecording() {
    if (!voiceIsRecording || !voiceMediaRecorder) return;
    voiceIsRecording = false;
    document.getElementById('voiceMicBtn').classList.remove('recording');

    const recorder = voiceMediaRecorder;
    voiceMediaRecorder = null;
    if (recorder.state !== 'inactive') {
        try {
            recorder.stop();
        } catch (e) {
            console.warn('stop recording failed', e);
            setVoiceStatus('待机', false);
        }
    }
}

function initVoiceChatTab() {
    const micBtn = document.getElementById('voiceMicBtn');
    if (!micBtn || micBtn.dataset.bound === '1') return;
    micBtn.dataset.bound = '1';

    micBtn.addEventListener('mousedown', function (e) { e.preventDefault(); startVoiceRecording(); });
    micBtn.addEventListener('mouseup', function (e) { e.preventDefault(); stopVoiceRecording(); });
    micBtn.addEventListener('pointerdown', function (e) {
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        e.preventDefault();
        startVoiceRecording();
    });
    micBtn.addEventListener('pointerup', function (e) { e.preventDefault(); stopVoiceRecording(); });
    micBtn.addEventListener('pointerleave', function () { if (voiceIsRecording) stopVoiceRecording(); });
    micBtn.addEventListener('touchstart', function (e) { e.preventDefault(); startVoiceRecording(); }, { passive: false });
    micBtn.addEventListener('touchend', function (e) { e.preventDefault(); stopVoiceRecording(); });

    document.getElementById('voiceChatForm').addEventListener('submit', async function (e) {
        e.preventDefault();
        const input = document.getElementById('voiceMessageInput');
        const message = input.value.trim();
        if (!message) return;
        input.value = '';
        await sendVoiceChatMessage(message, false);
    });

    loadVoiceList();
}

document.addEventListener('DOMContentLoaded', initVoiceChatTab);
