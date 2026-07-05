// ========== AskUserQuestion 技术选型 ==========
let askUserEventSource = null;
let askUserSessionId = null;
let askUserPendingQuestions = [];

function fillAskUserMessage(text) {
    document.getElementById('askUserMessageInput').value = text;
}

function appendAskUserMessage(role, content, options) {
    options = options || {};
    const area = document.getElementById('askUserChatArea');
    const div = document.createElement('div');
    div.className = 'message ' + role;
    const contentEl = document.createElement('div');
    contentEl.className = 'message-content' + (options.markdown ? ' markdown-body' : '');
    if (options.markdown) {
        contentEl.innerHTML = renderMarkdown(content || '');
    } else if (options.rawHtml) {
        contentEl.innerHTML = content;
    } else {
        contentEl.textContent = content;
    }
    div.appendChild(contentEl);
    area.appendChild(div);
    area.scrollTop = area.scrollHeight;
}

function closeAskUserEventSource() {
    if (askUserEventSource) {
        askUserEventSource.close();
        askUserEventSource = null;
    }
}

async function startAskUserChat() {
    const message = document.getElementById('askUserMessageInput').value.trim();
    if (!message) return;

    closeAskUserEventSource();
    document.getElementById('askUserQuestionPanel').style.display = 'none';
    document.getElementById('askUserQuestionPanel').innerHTML = '';
    document.getElementById('askUserChatArea').innerHTML = '';
    appendAskUserMessage('user', message);

    const btn = document.getElementById('askUserStartBtn');
    btn.disabled = true;

    try {
        const resp = await fetch('/agent/ask-user/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
        });
        const data = await resp.json();
        askUserSessionId = data.sessionId;

        askUserEventSource = new EventSource('/agent/ask-user/sse/' + askUserSessionId);
        askUserEventSource.onmessage = (event) => {
            const payload = JSON.parse(event.data);
            handleAskUserSseEvent(payload);
        };
        askUserEventSource.onerror = () => {
            appendAskUserMessage('assistant', '<span style="color:#d00">SSE 连接中断</span>', { rawHtml: true });
            closeAskUserEventSource();
            btn.disabled = false;
        };
    } catch (e) {
        appendAskUserMessage('assistant', '<span style="color:#d00">请求失败: ' + escapeHtml(e.message) + '</span>', { rawHtml: true });
        btn.disabled = false;
    }
}

function handleAskUserSseEvent(payload) {
    if (payload.type === 'RUNNING') {
        appendAskUserMessage('assistant', '<em>Agent 正在分析需求...</em>', { rawHtml: true });
    } else if (payload.type === 'QUESTIONS') {
        askUserPendingQuestions = payload.questions || [];
        renderAskUserQuestions(askUserPendingQuestions);
    } else if (payload.type === 'COMPLETED') {
        appendAskUserMessage('assistant', payload.response || '', { markdown: true });
        closeAskUserEventSource();
        document.getElementById('askUserStartBtn').disabled = false;
    } else if (payload.type === 'FAILED') {
        appendAskUserMessage('assistant', '<span style="color:#d00">' + escapeHtml(payload.error || '未知错误') + '</span>', { rawHtml: true });
        closeAskUserEventSource();
        document.getElementById('askUserStartBtn').disabled = false;
    }
}

function renderAskUserQuestions(questions) {
    const panel = document.getElementById('askUserQuestionPanel');
    panel.style.display = 'block';
    let html = '<div class="card"><div class="card-title">❓ Agent 需要你澄清以下问题</div><div class="card-body">';
    questions.forEach((q, qi) => {
        const inputType = q.multiSelect ? 'checkbox' : 'radio';
        const inputName = 'askq_' + qi;
        html += '<div style="margin-bottom:16px;padding:12px;background:#f9f9f9;border-radius:8px;">';
        html += '<strong>' + escapeHtml(q.header) + '</strong><p>' + escapeHtml(q.question) + '</p>';
        (q.options || []).forEach((opt) => {
            html += '<label style="display:block;margin:6px 0;">';
            html += '<input type="' + inputType + '" name="' + inputName + '" value="' + escapeHtml(opt.label) + '"> ';
            html += escapeHtml(opt.label) + ' - ' + escapeHtml(opt.description || '');
            html += '</label>';
        });
        html += '<input type="text" id="askq_custom_' + qi + '" placeholder="或输入自定义答案" style="width:100%;margin-top:8px;padding:8px;">';
        html += '</div>';
    });
    html += '<button class="btn btn-agent-tools" onclick="submitAskUserAnswers()">提交答案</button>';
    html += '</div></div>';
    panel.innerHTML = html;
}

async function submitAskUserAnswers() {
    const answers = {};
    askUserPendingQuestions.forEach((q, qi) => {
        const inputName = 'askq_' + qi;
        const custom = document.getElementById('askq_custom_' + qi).value.trim();
        if (custom) {
            answers[q.question] = custom;
            return;
        }
        const selected = Array.from(document.querySelectorAll('[name="' + inputName + '"]:checked'))
            .map(el => el.value);
        if (selected.length > 0) {
            answers[q.question] = selected.join(', ');
        }
    });

    document.getElementById('askUserQuestionPanel').style.display = 'none';
    appendAskUserMessage('user', '已提交澄清答案');

    await fetch('/agent/ask-user/answer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: askUserSessionId, answers })
    });
}

window.addEventListener('beforeunload', closeAskUserEventSource);
document.getElementById('askUserMessageInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); startAskUserChat(); }
});
