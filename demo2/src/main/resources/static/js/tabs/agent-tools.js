// ========== Agent Tools 工具调用 ==========
function fillAgentToolsDemand(demand) {
    document.getElementById('agentToolsDemandInput').value = demand;
    document.getElementById('agentToolsDemandInput').focus();
}

async function planTripWithTools() {
    const demand = document.getElementById('agentToolsDemandInput').value.trim();
    const resultBox = document.getElementById('agentToolsResult');
    const btn = document.getElementById('agentToolsPlanBtn');

    if (!demand) { alert('请输入出行需求'); return; }

    btn.disabled = true;
    resultBox.className = 'agent-tools-answer loading';
    resultBox.innerHTML = 'Agent 正在判断是否调用工具并生成行程（DeepSeek 推理中，工具调用需多轮对话，请稍候）...';

    try {
        const res = await fetch(`/agent/tool/plan?demand=${encodeURIComponent(demand)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        resultBox.className = 'agent-tools-answer';
        resultBox.innerHTML =
            `<div class="label">你的出行需求</div>` +
            `<div class="demand-text">${escapeHtml(data.userDemand)}</div>` +
            `<span class="tools-badge">${escapeHtml(data.agentType)}</span>` +
            `<div class="label">Agent Tools 规划结果（DeepSeek + 工具调用）</div>` +
            `<div>${escapeHtml(data.tripPlan)}</div>`;
    } catch (e) {
        resultBox.className = 'agent-tools-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认服务已启动，且 DeepSeek API Key 已配置（application.properties）。';
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('agentToolsDemandInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); planTripWithTools(); }
});
