// ========== AgentScope HarnessAgent ==========
let agentscopeAwaitingConfirm = false;

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
        + '输入排查问题获取检查清单，或询问 Java / Spring Boot 版本、源码结构、启动类。'
        + '可用「Workspace / AGENTS.md」示例验证项目规则注入。'
        + '可用「Compaction 四轮」示例，同一 session 连发四轮观察压缩提示。'
        + '写 notes/ 下文件会弹出确认卡片，可选择批准或拒绝。可点「换会话」验证 session 隔离。'
        + '</div></div>';
    document.getElementById('agentscopeSessionId').value = newAgentscopeSessionId();
    agentscopeAwaitingConfirm = false;
    setAgentscopeInputEnabled(true);
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

function beginAgentscopeAssistantTurn() {
    const box = document.getElementById('agentscopeMessages');
    const welcome = document.getElementById('agentscopeWelcome');
    if (welcome) welcome.remove();

    const wrap = document.createElement('div');
    wrap.className = 'message assistant';
    const col = document.createElement('div');
    col.className = 'agentscope-assistant-col';
    const strip = document.createElement('div');
    strip.className = 'agentscope-tool-strip';
    const content = document.createElement('div');
    content.className = 'message-content';
    col.appendChild(strip);
    col.appendChild(content);
    wrap.appendChild(col);
    box.appendChild(wrap);
    scrollAgentscopeMessages();
    return { col: col, strip: strip, content: content, tools: new Map() };
}

function upsertAgentscopeToolItem(turn, toolCallId, name, state) {
    if (!turn || !toolCallId) return;
    let item = turn.tools.get(toolCallId);
    if (!item) {
        item = document.createElement('div');
        item.className = 'agentscope-tool-item';
        item.dataset.toolCallId = toolCallId;
        turn.strip.appendChild(item);
        turn.tools.set(toolCallId, item);
    }
    item.classList.remove('is-done', 'is-error');
    if (state) {
        const upper = String(state).toUpperCase();
        if (upper === 'SUCCESS') item.classList.add('is-done');
        if (upper === 'ERROR' || upper === 'DENIED') item.classList.add('is-error');
        item.textContent = (name || 'tool') + ' · ' + state;
    } else {
        item.textContent = '准备调用：' + (name || 'tool');
    }
    scrollAgentscopeMessages();
}

function appendAgentscopeSystemMessage(text) {
    const box = document.getElementById('agentscopeMessages');
    if (!box) return;
    const welcome = document.getElementById('agentscopeWelcome');
    if (welcome) welcome.remove();
    const div = document.createElement('div');
    div.className = 'message system';
    const content = document.createElement('div');
    content.className = 'message-content';
    content.textContent = text || '';
    div.appendChild(content);
    box.appendChild(div);
    scrollAgentscopeMessages();
}

function fillAgentscopeSample(n) {
    const samples = {
        1: '帮我整理一份今天排查订单接口超时的执行清单',
        2: '支付回调偶发 500，给我一份不超过 6 步的排查顺序',
        3: '帮我看一下这个项目用了哪个 Java 版本、Spring Boot 版本，以及启动类在哪里',
        4: '请创建 notes/permission-demo.txt，内容是：AgentScope Permission HITL 已通过。',
        5: '按项目规则回答：当前项目名称、项目理解任务编号和三步理解顺序。不要调用工具。',
        6: '任务编号是 CTX-009。需要确认 Java 版本、Spring Boot 版本、启动类、源码目录、构建命令和测试命令。只确认收到，不要调用工具。'
    };
    const input = document.getElementById('agentscopeMessageInput');
    if (input) {
        input.value = samples[n] || '';
        input.focus();
    }
    if (n === 5) {
        const userId = document.getElementById('agentscopeUserId');
        if (userId) userId.value = 'workspace-user-008';
    }
    if (n === 6) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'context-user-009';
        if (sessionId) sessionId.value = 'context-session-009';
    }
}

function handleAgentscopeSsePayload(turn, payload, sessionId) {
    if (payload.type === 'SESSION') {
        setAgentscopeStatus('SESSION ' + (payload.sessionId || sessionId));
    } else if (payload.type === 'AGENT_START' || payload.type === 'MODEL_CALL_START' || payload.type === 'AGENT_END') {
        setAgentscopeStatus(payload.type);
    } else if (payload.type === 'TOOL_CALL_START') {
        setAgentscopeStatus('TOOL_CALL_START ' + (payload.name || ''));
        upsertAgentscopeToolItem(turn, payload.toolCallId, payload.name, null);
    } else if (payload.type === 'TOOL_RESULT_END') {
        setAgentscopeStatus('TOOL_RESULT_END ' + (payload.state || ''));
        upsertAgentscopeToolItem(turn, payload.toolCallId, payload.name, payload.state);
    } else if (payload.type === 'MESSAGE') {
        setAgentscopeStatus('流式中…');
        turn.content.textContent += (payload.content || '');
        scrollAgentscopeMessages();
    } else if (payload.type === 'AGENT_RESULT') {
        setAgentscopeStatus('AGENT_RESULT');
    } else if (payload.type === 'COMPACTION') {
        setAgentscopeStatus('COMPACTION');
        appendAgentscopeSystemMessage(payload.content || '上下文已压缩');
    } else if (payload.type === 'DONE') {
        setAgentscopeStatus('DONE');
    } else if (payload.type === 'ERROR') {
        setAgentscopeStatus('ERROR');
        turn.content.textContent += (turn.content.textContent ? '\n' : '') + '[ERROR] ' + (payload.content || '出错');
    } else if (payload.type === 'REQUIRE_USER_CONFIRM') {
        setAgentscopeStatus('REQUIRE_USER_CONFIRM');
        renderAgentscopeConfirmCard(turn, payload);
        return true;
    } else if (payload.type === 'REQUEST_STOP') {
        setAgentscopeStatus('REQUEST_STOP ' + (payload.content || ''));
    }
    return false;
}

async function consumeAgentscopeSse(res, turn, sessionId) {
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let awaitingConfirm = false;
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
            if (handleAgentscopeSsePayload(turn, payload, sessionId)) {
                awaitingConfirm = true;
            }
        }
    }
    return { awaitingConfirm: awaitingConfirm };
}

function renderAgentscopeConfirmCard(turn, payload) {
    setAgentscopeInputEnabled(false);
    const card = document.createElement('div');
    card.className = 'agentscope-confirm-card';
    const calls = payload.pendingToolCalls || [];
    let body = '需要确认以下工具调用：\n';
    calls.forEach(function (c, i) {
        const input = c.input || {};
        const content = String(input.content || '');
        const clipped = content.length > 200 ? content.slice(0, 200) + '…' : content;
        body += (i + 1) + '. ' + (c.name || '') + '\n'
            + '   operation: ' + (input.operation || '') + '\n'
            + '   path: ' + (input.path || '') + '\n'
            + '   content: ' + clipped + '\n';
    });
    const pre = document.createElement('pre');
    pre.textContent = body;
    card.appendChild(pre);
    const actions = document.createElement('div');
    actions.className = 'agentscope-confirm-actions';
    const approveBtn = document.createElement('button');
    approveBtn.type = 'button';
    approveBtn.className = 'approve';
    approveBtn.textContent = '批准';
    const denyBtn = document.createElement('button');
    denyBtn.type = 'button';
    denyBtn.textContent = '拒绝';
    approveBtn.onclick = function () { submitAgentscopeConfirm(turn, card, true); };
    denyBtn.onclick = function () { submitAgentscopeConfirm(turn, card, false); };
    actions.appendChild(approveBtn);
    actions.appendChild(denyBtn);
    card.appendChild(actions);
    turn.col.appendChild(card);
    scrollAgentscopeMessages();
}

async function submitAgentscopeConfirm(turn, card, approved) {
    const sessionId = document.getElementById('agentscopeSessionId').value.trim();
    const userId = document.getElementById('agentscopeUserId').value.trim();
    const buttons = card.querySelectorAll('button');
    buttons.forEach(function (btn) { btn.disabled = true; });
    setAgentscopeInputEnabled(false);
    setAgentscopeStatus(approved ? '确认中（批准）…' : '确认中（拒绝）…');

    try {
        const body = { sessionId: sessionId, approved: approved };
        if (userId) body.userId = userId;
        const res = await fetch('/agentscope/dev-agent/confirm', {
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
        await consumeAgentscopeSse(res, turn, sessionId);
        card.remove();
    } catch (e) {
        setAgentscopeStatus('确认失败');
        turn.content.textContent += (turn.content.textContent ? '\n' : '') + '[ERROR] ' + (e.message || e);
        buttons.forEach(function (btn) { btn.disabled = false; });
    } finally {
        setAgentscopeInputEnabled(true);
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
    const turn = beginAgentscopeAssistantTurn();
    setAgentscopeInputEnabled(false);
    setAgentscopeStatus('连接中…');

    agentscopeAwaitingConfirm = false;
    try {
        const body = { sessionId: sessionId, message: message };
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

        const result = await consumeAgentscopeSse(res, turn, sessionId);
        agentscopeAwaitingConfirm = result.awaitingConfirm;
    } catch (e) {
        setAgentscopeStatus('失败');
        turn.content.textContent += (turn.content.textContent ? '\n' : '') + '[ERROR] ' + (e.message || e);
    } finally {
        if (!agentscopeAwaitingConfirm) {
            setAgentscopeInputEnabled(true);
        }
    }
}

document.getElementById('agentscopeForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendAgentscopeMessage();
});
document.getElementById('agentscopeMessageInput')?.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendAgentscopeMessage();
    }
});
document.getElementById('agentscopeNewSessionBtn')?.addEventListener('click', function () {
    resetAgentscopeConversation();
});
ensureAgentscopeSessionId();
