// ========== AgentScope HarnessAgent ==========
function newAgentscopeSessionId() {
    if (crypto.randomUUID) return crypto.randomUUID();
    return 'sess-' + Date.now() + '-' + Math.random().toString(16).slice(2);
}

function ensureAgentscopeSessionId() {
    const el = document.getElementById('agentscopeSessionId');
    if (el && !el.value.trim()) el.value = newAgentscopeSessionId();
}

function resetAgentscopeConversation() {
    const box = document.getElementById('agentscopeMessages');
    if (!box) return;
    box.innerHTML = '<div id="agentscopeWelcome" class="message assistant"><div class="message-content">'
        + '输入研发任务，获取可执行检查清单。可点「换会话」验证 session 隔离。'
        + '</div></div>';
    document.getElementById('agentscopeSessionId').value = newAgentscopeSessionId();
    setAgentscopeStatus('就绪');
}

function setAgentscopeStatus(text) {
    const el = document.getElementById('agentscopeStatus');
    if (el) el.textContent = text;
}

function setAgentscopeInputEnabled(enabled) {
    const input = document.getElementById('agentscopeMessageInput');
    const btn = document.getElementById('agentscopeSendBtn');
    if (input) input.disabled = !enabled;
    if (btn) btn.disabled = !enabled;
}

function scrollAgentscopeMessages() {
    const box = document.getElementById('agentscopeMessages');
    if (box) box.scrollTop = box.scrollHeight;
}

function appendAgentscopeBubble(text, isUser) {
    const box = document.getElementById('agentscopeMessages');
    const welcome = document.getElementById('agentscopeWelcome');
    if (welcome) welcome.remove();
    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    content.textContent = text || '';
    div.appendChild(content);
    box.appendChild(div);
    scrollAgentscopeMessages();
    return content;
}

function fillAgentscopeSample(n) {
    const samples = {
        1: '帮我整理一份今天排查订单接口超时的执行清单',
        2: '支付回调偶发 500，给我一份不超过 6 步的排查顺序'
    };
    const input = document.getElementById('agentscopeMessageInput');
    if (input) {
        input.value = samples[n] || '';
        input.focus();
    }
}

async function sendAgentscopeMessage() {
    ensureAgentscopeSessionId();
    const message = document.getElementById('agentscopeMessageInput').value.trim();
    const sessionId = document.getElementById('agentscopeSessionId').value.trim();
    const userId = document.getElementById('agentscopeUserId').value.trim();
    if (!message || !sessionId) return;

    appendAgentscopeBubble(message, true);
    document.getElementById('agentscopeMessageInput').value = '';
    const assistant = appendAgentscopeBubble('', false);
    setAgentscopeInputEnabled(false);
    setAgentscopeStatus('连接中…');

    try {
        const body = { sessionId, message };
        if (userId) body.userId = userId;
        const res = await fetch('/agentscope/dev-agent/ask', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify(body)
        });
        if (!res.ok) {
            throw new Error(await res.text() || ('HTTP ' + res.status));
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const parts = buffer.split('\n\n');
            buffer = parts.pop();
            for (const part of parts) {
                let data = '';
                part.split('\n').forEach(function (line) {
                    if (line.startsWith('data:')) data += line.slice(5).trim();
                });
                if (!data || data === '[DONE]') continue;
                const payload = JSON.parse(data);
                if (payload.type === 'SESSION') {
                    setAgentscopeStatus('SESSION ' + (payload.sessionId || sessionId));
                } else if (payload.type === 'MESSAGE') {
                    setAgentscopeStatus('流式中…');
                    assistant.textContent += (payload.content || '');
                    scrollAgentscopeMessages();
                } else if (payload.type === 'DONE') {
                    setAgentscopeStatus('DONE');
                } else if (payload.type === 'ERROR') {
                    setAgentscopeStatus('ERROR');
                    assistant.textContent += (assistant.textContent ? '\n' : '') + '[ERROR] ' + (payload.content || '出错');
                }
            }
        }
    } catch (e) {
        setAgentscopeStatus('失败');
        assistant.textContent += (assistant.textContent ? '\n' : '') + '[ERROR] ' + (e.message || e);
    } finally {
        setAgentscopeInputEnabled(true);
    }
}

document.getElementById('agentscopeForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendAgentscopeMessage();
});
document.getElementById('agentscopeNewSessionBtn')?.addEventListener('click', function () {
    resetAgentscopeConversation();
});
ensureAgentscopeSessionId();
