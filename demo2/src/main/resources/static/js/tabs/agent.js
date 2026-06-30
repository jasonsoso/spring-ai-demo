// ========== Agent 行程规划 ==========
function fillAgentDemand(demand) {
    document.getElementById('agentDemandInput').value = demand;
    document.getElementById('agentDemandInput').focus();
}

async function planTrip() {
    const demand = document.getElementById('agentDemandInput').value.trim();
    const resultBox = document.getElementById('agentResult');
    const btn = document.getElementById('agentPlanBtn');

    if (!demand) { alert('请输入出行需求'); return; }

    btn.disabled = true;
    resultBox.className = 'agent-answer loading';
    resultBox.innerHTML = 'Agent 正在解析需求并规划行程，请稍候（DeepSeek 推理中）...';

    try {
        const res = await fetch(`/agent/trip/plan?demand=${encodeURIComponent(demand)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        resultBox.className = 'agent-answer';
        resultBox.innerHTML =
            `<div class="label">你的出行需求</div>` +
            `<div class="demand-text">${escapeHtml(data.userDemand)}</div>` +
            `<span class="agent-badge">${escapeHtml(data.agentType)}</span>` +
            `<div class="label">Agent 行程规划结果（DeepSeek 生成）</div>` +
            `<div>${escapeHtml(data.tripPlan)}</div>`;
    } catch (e) {
        resultBox.className = 'agent-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认服务已启动，且 DeepSeek API Key 已配置（application.properties）。';
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('agentDemandInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); planTrip(); }
});
