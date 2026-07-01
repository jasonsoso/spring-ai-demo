// ========== Session 事件溯源记忆 ==========

const SESSION_MEMORY_SAMPLES = {
    1: '周末两天杭州游，2人，偏好西湖人文景点，素食，地铁出行。请先查杭州天气。',
    2: '推荐几个人文景点，并结合天气给行程建议。',
    3: '我第一轮说的饮食禁忌是什么？请用 conversation_search 查找。',
    4: '周末一天苏州游，1人，园林景点，不吃辣，高铁+地铁。'
};

function fillSessionMemorySample(n) {
    const text = SESSION_MEMORY_SAMPLES[n];
    if (!text) return;
    document.getElementById('sessionMemoryMessageInput').value = text;
    document.getElementById('sessionMemoryMessageInput').focus();
}

function setSessionMemoryInputEnabled(enabled) {
    document.getElementById('sessionMemoryMessageInput').disabled = !enabled;
    document.getElementById('sessionMemorySendBtn').disabled = !enabled;
}

function scrollSessionMessages() {
    const box = document.getElementById('sessionMemoryMessages');
    box.scrollTop = box.scrollHeight;
}

function appendSessionBubble(text, isUser) {
    const box = document.getElementById('sessionMemoryMessages');
    const welcome = document.getElementById('sessionMemoryWelcome');
    if (welcome) welcome.remove();

    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    content.textContent = text;
    div.appendChild(content);
    box.appendChild(div);
    scrollSessionMessages();
    return content;
}

async function refreshSessionMemoryEvents() {
    const userId = document.getElementById('sessionMemoryUserIdInput').value.trim();
    const panel = document.getElementById('sessionMemoryEventPanel');
    if (!userId) {
        panel.innerHTML = '<p>请先填写 userId</p>';
        return;
    }
    try {
        const res = await fetch('/agent/session-memory/events?userId=' + encodeURIComponent(userId));
        if (!res.ok) throw new Error(await res.text() || 'HTTP ' + res.status);
        const data = await res.json();

        let html = '<h3>Event Store 摘要</h3>';
        html += '<div class="session-memory-stat"><span>总计</span><strong>' + data.totalEvents + '</strong></div>';
        html += '<div class="session-memory-stat"><span>Active（非 synthetic）</span><strong>' + data.activeEvents + '</strong></div>';
        html += '<div class="session-memory-stat"><span>Archived 估算</span><strong>' + data.archivedEvents + '</strong></div>';
        html += '<div class="session-memory-stat"><span>Synthetic 摘要</span><strong>' + data.syntheticEvents + '</strong></div>';
        html += '<div class="session-memory-event-list">';
        (data.events || []).forEach(function (ev) {
            html += '<div class="session-memory-event-item">';
            html += escapeHtml(ev.messageType) + ' · ' + escapeHtml(ev.eventId.substring(0, 8)) + '…';
            if (ev.synthetic) html += ' <span style="color:#7c3aed">[SYNTHETIC]</span>';
            if (ev.hasToolCalls) html += ' <span style="color:#059669">[TOOL]</span>';
            html += '<br><span style="color:#9ca3af">' + escapeHtml(ev.timestamp) + '</span>';
            html += '</div>';
        });
        html += '</div>';
        panel.innerHTML = html;
    } catch (e) {
        panel.innerHTML = '<p style="color:#b91c1c">加载失败：' + escapeHtml(e.message) + '</p>';
    }
}

async function clearSessionMemory() {
    const userId = document.getElementById('sessionMemoryUserIdInput').value.trim();
    if (!userId) { alert('请输入 userId'); return; }
    if (!confirm('确认清除 userId=' + userId + ' 的 Session 及全部事件？')) return;
    try {
        const res = await fetch('/agent/session-memory/clear?userId=' + encodeURIComponent(userId), { method: 'DELETE' });
        if (!res.ok) throw new Error(await res.text() || 'HTTP ' + res.status);
        document.getElementById('sessionMemoryMessages').innerHTML =
            '<div id="sessionMemoryWelcome" class="message assistant"><div class="message-content">会话已清除，可重新开始多轮对话。</div></div>';
        await refreshSessionMemoryEvents();
    } catch (e) {
        alert('清除失败：' + e.message);
    }
}

async function sendSessionMemoryMessage() {
    const userId = document.getElementById('sessionMemoryUserIdInput').value.trim();
    const message = document.getElementById('sessionMemoryMessageInput').value.trim();
    if (!userId) { alert('请输入 userId'); return; }
    if (!message) return;

    document.getElementById('sessionMemoryMessageInput').value = '';
    appendSessionBubble(message, true);
    const assistantBubble = appendSessionBubble('', false);
    setSessionMemoryInputEnabled(false);

    try {
        const response = await fetch('/agent/session-memory/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, message })
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
                if (evt.type === 'TOKEN' && evt.content) {
                    assistantBubble.textContent += evt.content;
                    scrollSessionMessages();
                } else if (evt.type === 'FAILED') {
                    throw new Error(evt.error || 'Agent 失败');
                }
            }
        }
        await refreshSessionMemoryEvents();
    } catch (e) {
        assistantBubble.textContent = '错误：' + e.message;
        assistantBubble.classList.add('error');
    } finally {
        setSessionMemoryInputEnabled(true);
        document.getElementById('sessionMemoryMessageInput').focus();
    }
}

document.getElementById('sessionMemoryForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendSessionMemoryMessage();
});

document.getElementById('sessionMemoryMessageInput')?.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendSessionMemoryMessage();
    }
});
