// ========== Embabel 自动选路 ==========
const EMBABEL_SAMPLES = {
    1: '给李白写一段白羊座今日运势文案',
    2: '出差回来后报销需要哪些材料'
};

function fillEmbabelSample(n) {
    const input = document.getElementById('embabelMessageInput');
    if (!input) return;
    input.value = EMBABEL_SAMPLES[n] || '';
    input.focus();
}

function setEmbabelInputEnabled(enabled) {
    const input = document.getElementById('embabelMessageInput');
    const btn = document.getElementById('embabelSendBtn');
    if (input) input.disabled = !enabled;
    if (btn) btn.disabled = !enabled;
}

function scrollEmbabelMessages() {
    const box = document.getElementById('embabelMessages');
    if (box) box.scrollTop = box.scrollHeight;
}

function appendEmbabelBubble(text, isUser) {
    const box = document.getElementById('embabelMessages');
    const welcome = document.getElementById('embabelWelcome');
    if (welcome) welcome.remove();

    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    if (isUser) {
        content.textContent = text;
    }
    div.appendChild(content);
    box.appendChild(div);
    scrollEmbabelMessages();
    return content;
}

async function sendEmbabelMessage() {
    const message = document.getElementById('embabelMessageInput').value.trim();
    if (!message) return;

    appendEmbabelBubble(message, true);
    document.getElementById('embabelMessageInput').value = '';

    const assistant = appendEmbabelBubble('', false);
    const progressEl = document.createElement('div');
    progressEl.className = 'embabel-progress';
    assistant.appendChild(progressEl);
    const resultEl = document.createElement('pre');
    resultEl.className = 'embabel-result-json';
    assistant.appendChild(resultEl);

    setEmbabelInputEnabled(false);
    try {
        const res = await fetch('/embabel/agent/ask/stream', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify({ message })
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
                let event = 'message';
                let data = '';
                part.split('\n').forEach(function (line) {
                    if (line.startsWith('event:')) event = line.slice(6).trim();
                    if (line.startsWith('data:')) data += line.slice(5).trim();
                });
                if (!data) continue;
                const payload = JSON.parse(data);
                if (event === 'AGENT_SELECTED') {
                    progressEl.innerHTML += '<div>已选择 Agent：<strong>' +
                        escapeHtml(payload.agentName) + '</strong></div>';
                } else if (event === 'PROGRESS' || event === 'ACTION_START' || event === 'ACTION_COMPLETE') {
                    const line = payload.text || payload.action || JSON.stringify(payload);
                    progressEl.innerHTML += '<div class="embabel-progress-line">' +
                        escapeHtml(line) + '</div>';
                } else if (event === 'RESULT') {
                    resultEl.textContent = JSON.stringify(payload, null, 2);
                } else if (event === 'ERROR') {
                    progressEl.innerHTML += '<div style="color:#b91c1c">' +
                        escapeHtml(payload.message || '出错') + '</div>';
                }
                scrollEmbabelMessages();
            }
        }
    } catch (e) {
        progressEl.innerHTML += '<div style="color:#b91c1c">' + escapeHtml(e.message) + '</div>';
    } finally {
        setEmbabelInputEnabled(true);
    }
}

document.getElementById('embabelForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendEmbabelMessage();
});
