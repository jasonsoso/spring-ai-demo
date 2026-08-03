// ========== AgentScope HarnessAgent ==========
let agentscopeAwaitingConfirm = false;
let agentscopeConfirmInFlight = false;
let agentscopeRequestInFlight = false;
let agentscopeProtocol = 'dev-agent'; // 'dev-agent' | 'agui'
let agentscopeAbortController = null;
const AGENTSCOPE_REQUEST_TIMEOUT_MS = 120000;
const AGENTSCOPE_AGUI_HITL_SAMPLES = new Set([4, 9, 13, 15]);

function newAgentscopeSessionId() {
    if (crypto.randomUUID) return crypto.randomUUID();
    return 'sess-' + Date.now() + '-' + Math.random().toString(16).slice(2);
}

function ensureAgentscopeSessionId() {
    const el = document.getElementById('agentscopeSessionId');
    if (el && !el.value.trim()) el.value = newAgentscopeSessionId();
}

function getAgentscopeProtocol() {
    const el = document.getElementById('agentscopeProtocol');
    return (el && el.value) || agentscopeProtocol || 'dev-agent';
}

function abortAgentscopeInFlight() {
    if (agentscopeAbortController) {
        try { agentscopeAbortController.abort('protocol-switch'); } catch (_) { /* ignore */ }
        agentscopeAbortController = null;
    }
}

function resetAgentscopeConversation() {
    abortAgentscopeInFlight();
    const box = document.getElementById('agentscopeMessages');
    if (!box) return;
    box.innerHTML = '<div id="agentscopeWelcome" class="message assistant"><div class="message-content">'
        + '输入排查问题获取检查清单，或询问 Java / Spring Boot 版本、源码结构、启动类。'
        + '可用「Workspace / AGENTS.md」示例验证项目规则注入。'
        + '可用「Compaction 七轮」示例，同一 session 连发观察压缩提示。'
        + '可用「Memory 记住约定」后点「新开会话（保留 userId）」再用「跨会话提问」验证长期记忆。'
        + '可用「Code Review Skill」验证动态 Skill + MCP 读样例。'
        + '可用「Code Review SubAgent」验证三角色委派与 source。'
        + '写 notes/ 下文件会弹出确认卡片；memory_save 在默认配置下也会确认。'
        + '可选 RAG 模式验证知识库（临时废弃 API）。'
        + '协议可选 DevAgent（全能力）或 AG-UI（文本/工具演示）。'
        + '</div></div>';
    document.getElementById('agentscopeSessionId').value = newAgentscopeSessionId();
    agentscopeAwaitingConfirm = false;
    agentscopeConfirmInFlight = false;
    agentscopeRequestInFlight = false;
    setAgentscopeInputEnabled(true);
    const proto = getAgentscopeProtocol();
    setAgentscopeStatus(proto === 'agui' ? '就绪（AG-UI）' : '就绪（DevAgent）');
}

function switchAgentscopeProtocol(next) {
    const value = next === 'agui' ? 'agui' : 'dev-agent';
    agentscopeProtocol = value;
    const el = document.getElementById('agentscopeProtocol');
    if (el) el.value = value;
    resetAgentscopeConversation();
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
    return {
        col: col,
        strip: strip,
        content: content,
        tools: new Map(),
        requestContext: null,
        errorRendered: false
    };
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
    if (getAgentscopeProtocol() === 'agui' && AGENTSCOPE_AGUI_HITL_SAMPLES.has(n)) {
        appendAgentscopeSystemMessage('当前为 AG-UI 演示模式，写文件 / Memory 确认 / 沙箱改码 / Plan Mode 请切回 DevAgent 协议。');
        setAgentscopeStatus('请切回 DevAgent');
        return;
    }
    const samples = {
        1: '帮我整理一份今天排查订单接口超时的执行清单',
        2: '支付回调偶发 500，给我一份不超过 6 步的排查顺序',
        3: '帮我看一下这个项目用了哪个 Java 版本、Spring Boot 版本，以及启动类在哪里',
        4: '请创建 notes/permission-demo.txt，内容是：AgentScope Permission HITL 已通过。',
        5: '按项目规则回答：当前项目名称、项目理解任务编号和三步理解顺序。不要调用工具。',
        6: '任务编号是 CTX-009。需要确认 Java 版本、Spring Boot 版本、启动类、源码目录、构建命令和测试命令。只确认收到，不要调用工具。',
        7: '请先列出 MCP 资料目录，再读取 project-profile.md，告诉我项目编号、Java 版本、Spring Boot 版本和维护团队。',
        8: '请必须调用 read_text_file 读取 C:\\Windows\\System32\\drivers\\etc\\hosts，并告诉我工具返回了什么。不要只根据规则直接回答。',
        9: '请记住下面三条项目约定：构建统一使用 Maven Wrapper；测试命令是 ./mvnw test；发布窗口是每周四 20:00。保存后简短确认。',
        10: '我们项目使用什么构建方式？测试命令是什么？发布窗口安排在什么时候？不要调用项目文件工具。',
        11: '请审查 MCP 资料目录里的 UserProfileFormatter.java，并给出是否适合合并的结论。',
        12: '请用 SubAgent 多角色审查 MCP 资料目录里的 TravelBudgetService.java，并给出是否适合合并的结论。',
        13: '请在沙箱中运行测试，修复 RetryPolicy 首次重试延迟翻倍的问题，并重新运行测试。',
        14: '请审查 RetryPolicy.delayMillis 的改动：原实现第一次重试使用第二档延迟，修改后第一次应为 1000ms，第二次 2000ms，第三次 4000ms。请给出结论、风险和建议。',
        15: '请先调查 workspace/project 里 RetryPolicy 第一次重试延迟错误，整理修复方案。方案确认前不要改代码，等我确认后再执行。',
        16: '根据项目约定说明 Plan Mode 应该怎么进入？进入后哪些工具可用、哪些不能用？请先检索知识库再回答。',
        17: '请说明 ragMode 的 NONE、GENERIC、AGENTIC 分别是什么含义，以及本演示默认是哪一种。请先检索知识库。',
        18: 'Docker Sandbox 模式下 execute 的 working_directory 应该填什么？read_file/edit_file 的 path 有什么约束？请先检索知识库。'
    };
    const input = document.getElementById('agentscopeMessageInput');
    if (input) {
        input.value = samples[n] || '';
        input.focus();
    }
    if (n === 16 || n === 17 || n === 18) {
        const ragMode = document.getElementById('agentscopeRagMode');
        if (ragMode) ragMode.value = n === 16 ? 'GENERIC' : 'AGENTIC';
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'rag-user-018';
        if (sessionId) sessionId.value = 'rag-session-018-' + n;
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
    if (n === 7 || n === 8) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'mcp-user-011';
        if (sessionId) {
            sessionId.value = n === 7 ? 'mcp-session-011' : 'mcp-outside-011';
        }
    }
    if (n === 9 || n === 10) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'memory-user-012';
        if (sessionId) {
            sessionId.value = n === 9 ? 'memory-session-a-012' : 'memory-session-b-012';
        }
    }
    if (n === 11) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'skill-user-013';
        if (sessionId) sessionId.value = 'skill-session-013';
    }
    if (n === 12) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'subagent-user-014';
        if (sessionId) sessionId.value = 'subagent-session-014';
    }
    if (n === 13) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'sandbox-user-015';
        if (sessionId) sessionId.value = 'sandbox-session-015';
    }
    if (n === 14) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'risk-user-016';
        if (sessionId) sessionId.value = 'risk-session-016';
    }
    if (n === 15) {
        const userId = document.getElementById('agentscopeUserId');
        const sessionId = document.getElementById('agentscopeSessionId');
        if (userId) userId.value = 'plan-user-017';
        if (sessionId) sessionId.value = 'plan-session-017';
    }
}

function renderAgentscopeError(turn, message) {
    if (!turn || turn.errorRendered) return;
    turn.errorRendered = true;
    turn.content.textContent += (turn.content.textContent ? '\n' : '')
        + '[ERROR] ' + (message || '出错');

    const requestId = turn.requestContext?.requestId;
    if (!requestId) return;

    const row = document.createElement('div');
    row.className = 'agentscope-error-request';
    const label = document.createElement('span');
    label.textContent = '请求编号：' + requestId;
    const copy = document.createElement('button');
    copy.type = 'button';
    copy.textContent = '复制';
    copy.onclick = async function () {
        try {
            await navigator.clipboard.writeText(requestId);
            copy.textContent = '已复制';
        } catch (e) {
            copy.textContent = '复制失败';
        }
    };
    row.appendChild(label);
    row.appendChild(copy);
    turn.col.appendChild(row);
    scrollAgentscopeMessages();
}

function handleAgentscopeSsePayload(turn, payload, sessionId) {
    if (payload.type === 'SESSION') {
        setAgentscopeStatus('SESSION ' + (payload.sessionId || sessionId));
    } else if (payload.type === 'REQUEST_CONTEXT') {
        turn.requestContext = {
            requestId: payload.requestId || '',
            traceId: payload.traceId || '-',
            spanId: payload.spanId || '-'
        };
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
    } else if (payload.type === 'WORKSPACE_DIFF') {
        setAgentscopeStatus('WORKSPACE_DIFF');
        renderAgentscopeDiffCard(turn, payload);
    } else if (payload.type === 'COMPACTION') {
        setAgentscopeStatus('COMPACTION');
        appendAgentscopeSystemMessage(payload.content || '上下文已压缩');
    } else if (payload.type === 'DONE') {
        setAgentscopeStatus('DONE');
    } else if (payload.type === 'ERROR') {
        setAgentscopeStatus('ERROR');
        renderAgentscopeError(turn, payload.content || '出错');
    } else if (payload.type === 'REQUIRE_USER_CONFIRM') {
        setAgentscopeStatus('REQUIRE_USER_CONFIRM');
        renderAgentscopeConfirmCard(turn, payload);
        return true;
    } else if (payload.type === 'REQUEST_STOP') {
        setAgentscopeStatus('REQUEST_STOP ' + (payload.content || ''));
    }
    return false;
}

function handleAgentscopeAguiPayload(turn, payload) {
    const type = payload.type;
    if (type === 'RUN_STARTED') {
        setAgentscopeStatus('RUN_STARTED');
    } else if (type === 'TEXT_MESSAGE_START') {
        turn.aguiMessageId = payload.messageId || turn.aguiMessageId;
        setAgentscopeStatus('TEXT_MESSAGE_START');
    } else if (type === 'TEXT_MESSAGE_CONTENT') {
        setAgentscopeStatus('流式中…');
        turn.content.textContent += (payload.delta || payload.content || '');
        scrollAgentscopeMessages();
    } else if (type === 'TEXT_MESSAGE_END') {
        setAgentscopeStatus('TEXT_MESSAGE_END');
    } else if (type === 'TOOL_CALL_START') {
        const id = payload.toolCallId || payload.id;
        const name = payload.toolCallName || payload.name || 'tool';
        setAgentscopeStatus('TOOL_CALL_START ' + name);
        upsertAgentscopeToolItem(turn, id, name, null);
    } else if (type === 'TOOL_CALL_ARGS') {
        setAgentscopeStatus('TOOL_CALL_ARGS');
    } else if (type === 'TOOL_CALL_END') {
        const id = payload.toolCallId || payload.id;
        upsertAgentscopeToolItem(turn, id, payload.toolCallName || payload.name, 'END');
    } else if (type === 'TOOL_CALL_RESULT') {
        const id = payload.toolCallId || payload.id;
        upsertAgentscopeToolItem(turn, id, payload.toolCallName || payload.name, 'SUCCESS');
        setAgentscopeStatus('TOOL_CALL_RESULT');
    } else if (type === 'RUN_FINISHED') {
        setAgentscopeStatus('RUN_FINISHED');
    } else if (type === 'RUN_ERROR') {
        setAgentscopeStatus('RUN_ERROR');
        renderAgentscopeError(turn, payload.message || payload.content || 'AG-UI RUN_ERROR');
    }
}

async function consumeAgentscopeAguiSse(res, turn) {
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
            let payload;
            try {
                payload = JSON.parse(data);
            } catch (_) {
                continue;
            }
            handleAgentscopeAguiPayload(turn, payload);
        }
    }
}

function renderAgentscopeDiffCard(turn, payload) {
    const card = document.createElement('div');
    card.className = 'agentscope-confirm-card';
    const pre = document.createElement('pre');
    pre.textContent = payload.content || '没有检测到文件变更。';
    card.appendChild(pre);
    const actions = document.createElement('div');
    actions.className = 'agentscope-confirm-actions';
    ['批准回写', '拒绝回写'].forEach(function (label, index) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = index === 0 ? 'approve' : '';
        button.textContent = label;
        button.onclick = function () {
            submitAgentscopeDiff(turn, card, payload.eventId, index === 0);
        };
        actions.appendChild(button);
    });
    card.appendChild(actions);
    turn.col.appendChild(card);
    setAgentscopeInputEnabled(false);
    scrollAgentscopeMessages();
}

async function submitAgentscopeDiff(turn, card, diffId, approved) {
    card.querySelectorAll('button').forEach(function (button) { button.disabled = true; });
    const userId = document.getElementById('agentscopeUserId').value.trim();
    const sessionId = document.getElementById('agentscopeSessionId').value.trim();
    const controller = new AbortController();
    const timeoutId = setTimeout(function () {
        controller.abort('Diff 回写请求超时');
    }, AGENTSCOPE_REQUEST_TIMEOUT_MS);
    try {
        const response = await fetch('/agentscope/dev-agent/apply-diff', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            signal: controller.signal,
            body: JSON.stringify({ userId, sessionId, diffId, approved })
        });
        const result = await response.json();
        appendAgentscopeSystemMessage(result.message || 'Diff 操作完成');
    } catch (error) {
        renderAgentscopeError(
            turn,
            error.name === 'AbortError'
                ? 'Diff 回写超过 120 秒，已取消等待。'
                : 'Diff 回写失败：' + error.message);
    } finally {
        clearTimeout(timeoutId);
        setAgentscopeInputEnabled(true);
    }
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

function clipAgentscopeConfirmValue(value, maxLen) {
    const text = value == null ? '' : String(value);
    const limit = maxLen == null ? 200 : maxLen;
    return text.length > limit ? text.slice(0, limit) + '…' : text;
}

/** 按工具名展示 HITL 确认参数（execute / edit_file / 写文件类）。 */
function formatAgentscopeConfirmInput(name, input) {
    const tool = String(name || '');
    const src = input || {};
    const lines = [];
    function add(key, val, maxLen) {
        if (val == null || val === '') return;
        lines.push('   ' + key + ': ' + clipAgentscopeConfirmValue(val, maxLen));
    }
    if (tool === 'execute') {
        add('command', src.command, 400);
        add('working_directory', src.working_directory || src.workingDirectory);
        add('timeout', src.timeout);
        return lines;
    }
    if (tool === 'edit_file') {
        add('path', src.path || src.file_path || src.filePath);
        add('old_string', src.old_string || src.oldString, 300);
        add('new_string', src.new_string || src.newString, 300);
        return lines;
    }
    // request_file_change / write_file 等写文件类，以及未知工具兜底
    add('operation', src.operation);
    add('path', src.path || src.file_path || src.filePath);
    add('content', src.content, 200);
    add('command', src.command, 400);
    add('working_directory', src.working_directory || src.workingDirectory);
    add('old_string', src.old_string || src.oldString, 300);
    add('new_string', src.new_string || src.newString, 300);
    if (lines.length === 0) {
        Object.keys(src).forEach(function (key) {
            add(key, src[key], 200);
        });
    }
    return lines;
}

function renderAgentscopeConfirmCard(turn, payload) {
    setAgentscopeInputEnabled(false);
    const card = document.createElement('div');
    card.className = 'agentscope-confirm-card';
    const calls = payload.pendingToolCalls || [];
    let body = '需要确认以下工具调用：\n';
    calls.forEach(function (c, i) {
        const input = c.input || {};
        body += (i + 1) + '. ' + (c.name || '') + '\n';
        const detailLines = formatAgentscopeConfirmInput(c.name, input);
        body += detailLines.length ? detailLines.join('\n') + '\n' : '   (无参数)\n';
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
    if (agentscopeConfirmInFlight) return;
    agentscopeConfirmInFlight = true;
    const sessionId = document.getElementById('agentscopeSessionId').value.trim();
    const userId = document.getElementById('agentscopeUserId').value.trim();
    const buttons = card.querySelectorAll('button');
    buttons.forEach(function (btn) { btn.disabled = true; });
    setAgentscopeInputEnabled(false);
    setAgentscopeStatus(approved ? '确认中（批准）…' : '确认中（拒绝）…');
    turn.requestContext = null;
    turn.errorRendered = false;
    const controller = new AbortController();
    const timeoutId = setTimeout(function () {
        controller.abort('确认请求超时');
    }, AGENTSCOPE_REQUEST_TIMEOUT_MS);

    try {
        const body = { sessionId: sessionId, approved: approved };
        if (userId) body.userId = userId;
        const res = await fetch('/agentscope/dev-agent/confirm', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            signal: controller.signal,
            body: JSON.stringify(body)
        });
        if (!res.ok) {
            throw new Error(await res.text() || ('HTTP ' + res.status));
        }
        await consumeAgentscopeSse(res, turn, sessionId);
        card.remove();
    } catch (e) {
        const timedOut = e.name === 'AbortError';
        setAgentscopeStatus(timedOut ? '确认超时' : '确认失败');
        renderAgentscopeError(
            turn,
            timedOut ? '确认请求超过 120 秒，已取消本次等待，请检查服务端日志后重试。'
                : (e.message || String(e)));
        buttons.forEach(function (btn) { btn.disabled = false; });
    } finally {
        clearTimeout(timeoutId);
        agentscopeConfirmInFlight = false;
        setAgentscopeInputEnabled(true);
    }
}

async function sendAgentscopeMessage() {
    if (agentscopeRequestInFlight || agentscopeConfirmInFlight) return;
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
    agentscopeRequestInFlight = true;
    agentscopeAbortController = new AbortController();
    const protocol = getAgentscopeProtocol();
    try {
        if (protocol === 'agui') {
            const runId = newAgentscopeSessionId();
            const messageId = newAgentscopeSessionId();
            const res = await fetch('/agui/run', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'text/event-stream'
                },
                signal: agentscopeAbortController.signal,
                body: JSON.stringify({
                    threadId: sessionId,
                    runId: runId,
                    messages: [
                        { id: messageId, role: 'user', content: message }
                    ]
                })
            });
            if (!res.ok) {
                throw new Error(await res.text() || ('HTTP ' + res.status));
            }
            await consumeAgentscopeAguiSse(res, turn);
        } else {
            const body = { sessionId: sessionId, message: message };
            if (userId) body.userId = userId;
            const ragMode = (document.getElementById('agentscopeRagMode')?.value || 'NONE').trim();
            body.ragMode = ragMode;
            const res = await fetch('/agentscope/dev-agent/ask', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'text/event-stream'
                },
                signal: agentscopeAbortController.signal,
                body: JSON.stringify(body)
            });
            if (!res.ok) {
                throw new Error(await res.text() || ('HTTP ' + res.status));
            }
            const result = await consumeAgentscopeSse(res, turn, sessionId);
            agentscopeAwaitingConfirm = result.awaitingConfirm;
        }
    } catch (e) {
        if (e && (e.name === 'AbortError' || String(e.message || e).indexOf('protocol-switch') >= 0)) {
            setAgentscopeStatus('已取消（协议切换）');
        } else {
            setAgentscopeStatus('失败');
            renderAgentscopeError(turn, e.message || String(e));
        }
    } finally {
        agentscopeRequestInFlight = false;
        agentscopeAbortController = null;
        if (protocol === 'agui' || !agentscopeAwaitingConfirm) {
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
document.getElementById('agentscopeProtocol')?.addEventListener('change', function (e) {
    switchAgentscopeProtocol(e.target.value);
});
ensureAgentscopeSessionId();
agentscopeProtocol = getAgentscopeProtocol();
setAgentscopeStatus(agentscopeProtocol === 'agui' ? '就绪（AG-UI）' : '就绪（DevAgent）');
