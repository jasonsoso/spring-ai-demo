// ========== Embabel 自动选路 ==========
const EMBABEL_SAMPLES = {
    1: '给李白写一段白羊座今日运势文案',
    2: '出差回来后报销需要哪些材料',
    3: '请根据下面技术文章生成 3 道单选测验题。标题：为什么 Spring AI 的 Tool Calling 不等于完整 Agent。正文：Tool Calling 解决的是模型如何请求外部工具，以及应用侧如何执行这些工具。一次工具调用循环可以让模型先查资料、再继续回答，但它通常只发生在一次模型调用上下文里。Agent 更关注任务目标、状态、动作链路和停止条件。'
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

function renderEmbabelResult(resultEl, payload) {
    const outputType = payload.outputType;
    const output = payload.output;
    if (outputType === 'QuizPack' && output) {
        resultEl.className = 'embabel-quiz-pack';
        resultEl.innerHTML = buildQuizPackHtml(output);
        return;
    }
    resultEl.className = 'embabel-result-json';
    resultEl.textContent = JSON.stringify(payload, null, 2);
}

function buildQuizPackHtml(pack) {
    const letters = ['A', 'B', 'C', 'D'];
    let html = '<div class="embabel-quiz-title">' + escapeHtml(pack.title || '') + '</div>';
    const questions = pack.questions || [];
    questions.forEach(function (q, idx) {
        html += '<div class="embabel-quiz-q">';
        html += '<div class="embabel-quiz-stem">' + (idx + 1) + '. ' + escapeHtml(q.question || '') + '</div>';
        html += '<ul class="embabel-quiz-options">';
        (q.options || []).forEach(function (opt, oi) {
            const letter = letters[oi] || String(oi + 1);
            const isAnswer = opt === q.answer;
            html += '<li class="' + (isAnswer ? 'is-answer' : '') + '">'
                + '<strong>' + letter + '.</strong> ' + escapeHtml(opt)
                + (isAnswer ? ' <span class="embabel-quiz-badge">正确</span>' : '')
                + '</li>';
        });
        html += '</ul>';
        html += '<div class="embabel-quiz-explain"><strong>解释：</strong>'
            + escapeHtml(q.explanation || '') + '</div>';
        html += '</div>';
    });
    html += '<div class="embabel-quiz-review">' + escapeHtml(pack.review || '') + '</div>';
    return html;
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
    const resultEl = document.createElement('div');
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
                    renderEmbabelResult(resultEl, payload);
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
