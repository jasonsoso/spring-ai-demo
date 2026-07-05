// ========== Tool Reasoning 工具推理捕获 ==========
let toolReasoningSessionId = crypto.randomUUID();

const TOOL_REASONING_SAMPLES = {
    1: '北京明天天气怎么样？适合出行吗？',
    2: '推荐几个北京的人文景点，最好评分高的',
    3: '帮我规划北京周末两天游，先看看天气，再推荐几个人文景点，生成完整行程',
    4: '广州天气怎么样？如果下雨的话推荐室内景点'
};

function fillToolReasoningMessage(text) {
    document.getElementById('toolReasoningMessageInput').value = text;
    document.getElementById('toolReasoningMessageInput').focus();
}

function fillToolReasoningSample(n) {
    fillToolReasoningMessage(TOOL_REASONING_SAMPLES[n] || '');
}

function setToolReasoningInputEnabled(enabled) {
    document.getElementById('toolReasoningMessageInput').disabled = !enabled;
    document.getElementById('toolReasoningSendBtn').disabled = !enabled;
}

function scrollToolReasoningMessages() {
    const box = document.getElementById('toolReasoningMessages');
    box.scrollTop = box.scrollHeight;
}

function appendToolReasoningBubble(text, isUser) {
    const box = document.getElementById('toolReasoningMessages');
    const welcome = document.getElementById('toolReasoningWelcome');
    if (welcome) welcome.remove();

    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    if (isUser) {
        content.textContent = text;
    } else {
        content.innerHTML = '<div class="reasoning-inline-cards"></div><div class="response-text markdown-body"></div>';
    }
    div.appendChild(content);
    box.appendChild(div);
    scrollToolReasoningMessages();
    return content;
}

function confidenceClass(confidence) {
    const c = (confidence || '').toLowerCase();
    if (c === 'high') return 'confidence-high';
    if (c === 'low') return 'confidence-low';
    return 'confidence-medium';
}

function toolIcon(toolName) {
    if (!toolName) return '🔧';
    const lower = toolName.toLowerCase();
    if (lower.includes('weather')) return '🌤️';
    if (lower.includes('attraction')) return '🏛️';
    return '🔧';
}

function appendReasoningCard(payload, inlineContainer) {
    const card = document.createElement('div');
    card.className = 'reasoning-card';
    card.dataset.callIndex = payload.callIndex;
    card.innerHTML =
        '<div class="tool-name">' + toolIcon(payload.toolName) + ' #' + payload.callIndex +
        ' ' + escapeHtml(payload.toolName || 'tool') + '</div>' +
        '<div class="' + confidenceClass(payload.confidence) + '">置信度: ' +
        escapeHtml(payload.confidence || '—') + '</div>' +
        '<div class="inner-thought">' + escapeHtml(payload.innerThought || '') + '</div>';
    if (inlineContainer) inlineContainer.appendChild(card);

    const sidebar = document.getElementById('toolReasoningSidebarList');
    if (sidebar) sidebar.appendChild(card.cloneNode(true));
    scrollToolReasoningMessages();
}

async function sendToolReasoningMessage() {
    const message = document.getElementById('toolReasoningMessageInput').value.trim();
    if (!message) return;

    document.getElementById('toolReasoningMessageInput').value = '';
    appendToolReasoningBubble(message, true);
    const assistantContent = appendToolReasoningBubble('', false);
    const inlineCards = assistantContent.querySelector('.reasoning-inline-cards');
    const responseText = assistantContent.querySelector('.response-text');
    setToolReasoningInputEnabled(false);

    const stream = createMarkdownStreamRenderer(responseText, scrollToolReasoningMessages);

    try {
        const response = await fetch('/agent/tool-reasoning/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: toolReasoningSessionId, message })
        });
        if (!response.ok) throw new Error(await response.text() || 'HTTP ' + response.status);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';
            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed.startsWith('data:')) continue;
                const json = trimmed.replace(/^data:\s*/, '');
                if (!json) continue;
                const evt = JSON.parse(json);
                if (evt.type === 'TOOL_REASONING') {
                    appendReasoningCard(evt, inlineCards);
                } else if (evt.type === 'TOKEN' && evt.content) {
                    stream.append(evt.content);
                } else if (evt.type === 'COMPLETED') {
                    stream.flush();
                } else if (evt.type === 'FAILED') {
                    throw new Error(evt.error || 'Agent 失败');
                }
            }
        }
        stream.flush();
    } catch (e) {
        stream.setPlainError('错误：' + e.message);
        assistantContent.parentElement.classList.add('error');
    } finally {
        setToolReasoningInputEnabled(true);
        document.getElementById('toolReasoningMessageInput').focus();
    }
}

async function clearToolReasoningSession() {
    if (!confirm('确认清除当前 sessionId 的对话记忆？')) return;
    try {
        const res = await fetch('/agent/tool-reasoning/clear?sessionId=' +
            encodeURIComponent(toolReasoningSessionId), { method: 'DELETE' });
        if (!res.ok) throw new Error(await res.text() || 'HTTP ' + res.status);
        document.getElementById('toolReasoningMessages').innerHTML =
            '<div id="toolReasoningWelcome" class="message assistant"><div class="message-content">' +
            '会话已清除。发送消息开始多轮对话，工具调用时将实时展示 innerThought 与 confidence。</div></div>';
        document.getElementById('toolReasoningSidebarList').innerHTML = '';
        toolReasoningSessionId = crypto.randomUUID();
        document.getElementById('toolReasoningSessionIdDisplay').textContent = toolReasoningSessionId;
    } catch (e) {
        alert('清除失败：' + e.message);
    }
}

document.getElementById('toolReasoningForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendToolReasoningMessage();
});

document.addEventListener('DOMContentLoaded', function () {
    const el = document.getElementById('toolReasoningSessionIdDisplay');
    if (el) el.textContent = toolReasoningSessionId;
});
