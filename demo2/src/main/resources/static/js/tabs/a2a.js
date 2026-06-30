// ========== A2A 跨系统对话 ==========
function fillA2aMessage(text) {
    document.getElementById('a2aMessageInput').value = text;
    document.getElementById('a2aMessageInput').focus();
}

async function startA2aChat() {
    const message = document.getElementById('a2aMessageInput').value.trim();
    const resultBox = document.getElementById('a2aResult');
    const btn = document.getElementById('a2aStartBtn');

    if (!message) { alert('请输入问题'); return; }

    btn.disabled = true;
    resultBox.className = 'agent-tools-answer loading';
    resultBox.textContent = '⏳ A2A 对话中...\n\n① 协调器发现远程 Agent（/.well-known/agent-card.json）\n② 通过 A2A 协议委派天气专家\n③ 整合结果回复\n\n请耐心等待...';

    try {
        const res = await fetch(`/agent/a2a/chat?message=${encodeURIComponent(message)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        resultBox.className = 'agent-tools-answer';
        resultBox.innerHTML = `<div class="label">问题</div>
<div style="margin-bottom:12px;padding-bottom:12px;border-bottom:1px dashed #ddd;">${escapeHtml(data.message)}</div>
<div class="label">${escapeHtml(data.agentType || '')}</div>
<div style="white-space:pre-wrap;margin-top:8px;">${escapeHtml(data.response || '')}</div>`;
    } catch (e) {
        resultBox.className = 'agent-tools-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认服务已启动且 DEEPSEEK_API_KEY 已配置。';
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('a2aMessageInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); startA2aChat(); }
});
