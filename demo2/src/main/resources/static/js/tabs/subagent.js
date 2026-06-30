// ========== Subagent 编排 ==========
function fillSubagentMessage(text) {
    document.getElementById('subagentMessageInput').value = text;
    document.getElementById('subagentMessageInput').focus();
}

async function startSubagentChat() {
    const message = document.getElementById('subagentMessageInput').value.trim();
    const resultBox = document.getElementById('subagentResult');
    const btn = document.getElementById('subagentStartBtn');

    if (!message) { alert('请输入任务描述'); return; }

    btn.disabled = true;
    resultBox.className = 'agent-tools-answer loading';
    resultBox.textContent = '⏳ Subagent 编排中...\n\n① 主协调器评估任务\n② 委派 architect 产出 Blueprint\n③ 委派 builder 生成最终内容\n\n预计 30～90 秒，请耐心等待...';

    try {
        const res = await fetch(`/agent/subagent/chat?message=${encodeURIComponent(message)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        resultBox.className = 'agent-tools-answer';
        resultBox.innerHTML = `<div class="label">任务</div>
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

document.getElementById('subagentMessageInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); startSubagentChat(); }
});
