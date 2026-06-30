// ========== 多 Agent 协作 ==========
function fillMultiAgentDemand(demand) {
    document.getElementById('multiAgentDemandInput').value = demand;
    document.getElementById('multiAgentDemandInput').focus();
}

async function planTripMultiAgent() {
    const demand = document.getElementById('multiAgentDemandInput').value.trim();
    const resultBox = document.getElementById('multiAgentResult');
    const btn = document.getElementById('multiAgentPlanBtn');

    if (!demand) { alert('请输入出行需求'); return; }

    btn.disabled = true;
    resultBox.className = 'multi-agent-answer loading';
    resultBox.innerHTML = '⏳ 多 Agent 协作启动中...\n\n' +
        '① Coordinator Agent 正在分析需求...\n' +
        '② 四个专项 Agent（景点/餐饮/住宿/交通）将并行执行...\n' +
        '③ Synthesizer Agent 将整合所有结果...\n\n' +
        '预计耗时 20~60 秒，请耐心等待（共 6 次 DeepSeek 调用）...';

    try {
        const res = await fetch(`/agent/multi/plan?demand=${encodeURIComponent(demand)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        // 渲染结果
        resultBox.className = 'multi-agent-answer';
        resultBox.innerHTML = `
<div class="label">你的出行需求</div>
<div style="color:#c0002a;font-weight:500;margin-bottom:12px;padding-bottom:12px;border-bottom:1px dashed #ffc0c0;">${escapeHtml(data.userDemand)}</div>

<div class="label">① Coordinator 需求分析</div>
<div class="coordinator-box">${escapeHtml(data.taskBrief || '')}</div>

<div class="label">② 四个专项 Agent 并行规划结果</div>
<div class="agent-cards">
<div class="agent-card">
    <div class="agent-card-title attraction">🗺️ 景点规划 Agent</div>
    <div class="agent-card-body">${escapeHtml(data.attractionPlan || '')}</div>
</div>
<div class="agent-card">
    <div class="agent-card-title dining">🍜 餐饮规划 Agent</div>
    <div class="agent-card-body">${escapeHtml(data.diningPlan || '')}</div>
</div>
<div class="agent-card">
    <div class="agent-card-title accommodation">🏨 住宿规划 Agent</div>
    <div class="agent-card-body">${escapeHtml(data.accommodationPlan || '')}</div>
</div>
<div class="agent-card">
    <div class="agent-card-title transport">🚌 交通规划 Agent</div>
    <div class="agent-card-body">${escapeHtml(data.transportPlan || '')}</div>
</div>
</div>

<div class="label">③ Synthesizer 综合行程方案</div>
<span class="multi-agent-badge">${escapeHtml(data.agentType || '')}</span>
<span class="multi-agent-badge">总耗时 ${data.totalCostMs || 0}ms</span>
<div class="final-plan-box">${escapeHtml(data.finalPlan || '')}</div>`;
    } catch (e) {
        resultBox.className = 'multi-agent-answer error';
        resultBox.textContent = '请求失败：' + e.message +
            '\n\n请确认：\n1. 服务已启动\n2. DeepSeek API Key 已配置\n3. 网络连接正常（6次LLM调用，超时请重试）';
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('multiAgentDemandInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); planTripMultiAgent(); }
});
