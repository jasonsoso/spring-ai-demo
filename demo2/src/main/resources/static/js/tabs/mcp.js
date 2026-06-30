// ========== MCP Client 工具调用 ==========
function fillMcpQuestion(question) {
    document.getElementById('mcpQuestionInput').value = question;
    document.getElementById('mcpQuestionInput').focus();
}

async function loadMcpTools() {
    const btn = document.getElementById('loadMcpToolsBtn');
    const container = document.getElementById('mcpToolsList');
    btn.disabled = true;
    container.innerHTML = '<span style="color:#999;font-size:13px;">正在加载...</span>';
    try {
        const res = await fetch('/mcp/client/tools');
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const tools = await res.json();
        if (!tools || tools.length === 0) {
            container.innerHTML = '<span style="color:#999;font-size:13px;">暂无工具（MCP Client 可能尚未初始化）</span>';
        } else {
            container.innerHTML = tools.map(t => {
                const [name, ...desc] = t.split(' - ');
                return `<span class="tools-tag" title="${escapeHtml(desc.join(' - '))}">${escapeHtml(name)}</span>`;
            }).join('');
        }
    } catch (e) {
        container.innerHTML = `<span style="color:#d00;font-size:13px;">加载失败：${escapeHtml(e.message)}（请确认服务已完全启动）</span>`;
    } finally {
        btn.disabled = false;
    }
}

async function askMcp() {
    const question = document.getElementById('mcpQuestionInput').value.trim();
    const resultBox = document.getElementById('mcpResult');
    const btn = document.getElementById('mcpAskBtn');

    if (!question) { alert('请输入问题'); return; }

    btn.disabled = true;
    resultBox.className = 'mcp-answer loading';
    resultBox.innerHTML = 'MCP Client 正在通过 SSE 调用 MCP Server 工具（DeepSeek 推理中，工具调用需多轮，请稍候）...';

    try {
        const res = await fetch(`/mcp/client/chat?message=${encodeURIComponent(question)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const answer = await res.text();

        resultBox.className = 'mcp-answer';
        resultBox.innerHTML =
            `<div class="label">你的问题</div>` +
            `<div class="question-text">${escapeHtml(question)}</div>` +
            `<span class="mcp-badge">MCP Client</span>` +
            `<span class="mcp-badge">DeepSeek Chat</span>` +
            `<span class="mcp-badge">智谱 Embedding</span>` +
            `<div class="label">MCP 工具调用回答</div>` +
            `<div>${escapeHtml(answer)}</div>`;
    } catch (e) {
        resultBox.className = 'mcp-answer error';
        resultBox.textContent = '请求失败：' + e.message +
            '\n\n请确认：\n1. 服务已完全启动（MCP Client 初始化需等 Tomcat 就绪后约 1~2 秒）\n2. DeepSeek API Key 已配置\n3. 查看控制台日志确认 MCP Client 初始化成功';
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('mcpQuestionInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); askMcp(); }
});
