// ========== Agent 记忆行程 ==========
const MEMORY_TEST_CASES = {
    1: {
        userId: '1001',
        demand: '周末两天成都短途游，2人，偏好自然景点，不吃辣，交通以地铁和打车为主'
    },
    2: {
        userId: '1001',
        demand: '下周工作日三天，再规划一次成都周边游'
    },
    3: {
        userId: '1002',
        demand: '周末一天重庆游，1人，喜欢人文景点，吃辣，地铁出行'
    }
};

const MYSQL_MEMORY_TEST_CASES = {
    1: {
        userId: '1001',
        memoryType: 'message',
        demand: '周末两天厦门游，2人，偏好海滨景点，不吃海鲜，交通以地铁和网约车为主'
    },
    2: {
        userId: '1001',
        memoryType: 'message',
        demand: '下周工作日三天，规划一次泉州游'
    },
    3: {
        userId: '1001',
        memoryType: 'prompt',
        demand: '再规划一次漳州游'
    }
};

function fillMemoryTest(testNo) {
    const testCase = MEMORY_TEST_CASES[testNo];
    if (!testCase) return;
    document.getElementById('memoryUserIdInput').value = testCase.userId;
    document.getElementById('memoryDemandInput').value = testCase.demand;
    document.getElementById('memoryDemandInput').focus();
}

function fillMysqlMemoryTest(testNo) {
    const testCase = MYSQL_MEMORY_TEST_CASES[testNo];
    if (!testCase) return;
    document.getElementById('mysqlMemoryUserIdInput').value = testCase.userId;
    document.getElementById('mysqlMemoryTypeSelect').value = testCase.memoryType;
    document.getElementById('mysqlMemoryDemandInput').value = testCase.demand;
    document.getElementById('mysqlMemoryDemandInput').focus();
}

async function planTripWithMemory() {
    const userId = document.getElementById('memoryUserIdInput').value.trim();
    const demand = document.getElementById('memoryDemandInput').value.trim();
    const resultBox = document.getElementById('memoryResult');
    const btn = document.getElementById('memoryPlanBtn');

    if (!userId) { alert('请输入用户 ID'); return; }
    if (!demand) { alert('请输入出行需求'); return; }

    btn.disabled = true;
    resultBox.className = 'memory-answer loading';
    resultBox.innerHTML = `正在为 userId=${escapeHtml(userId)} 加载记忆并规划行程（DeepSeek 推理中）...`;

    try {
        const url = `/agent/trip/plan-with-memory?userId=${encodeURIComponent(userId)}&demand=${encodeURIComponent(demand)}`;
        const res = await fetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        resultBox.className = 'memory-answer';
        resultBox.innerHTML =
            `<div class="label">用户 ID</div>` +
            `<div class="demand-text">${escapeHtml(data.userId)}</div>` +
            `<div class="label">你的出行需求</div>` +
            `<div class="demand-text">${escapeHtml(data.userDemand)}</div>` +
            `<span class="memory-badge">${escapeHtml(data.agentType)}</span>` +
            `<span class="memory-badge">userId=${escapeHtml(data.userId)}</span>` +
            `<div class="label">带记忆行程规划结果（DeepSeek 生成）</div>` +
            `<div>${escapeHtml(data.tripPlan)}</div>`;
    } catch (e) {
        resultBox.className = 'memory-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认服务已启动，且 DeepSeek API Key 已配置（application.properties）。';
    } finally {
        btn.disabled = false;
    }
}

async function clearUserMemory() {
    const userId = document.getElementById('memoryUserIdInput').value.trim();
    const resultBox = document.getElementById('memoryResult');
    const btn = document.getElementById('memoryClearBtn');

    if (!userId) { alert('请输入用户 ID'); return; }

    btn.disabled = true;
    try {
        const res = await fetch(`/agent/trip/clear-memory?userId=${encodeURIComponent(userId)}`, { method: 'DELETE' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        resultBox.className = 'memory-answer';
        resultBox.innerHTML =
            `<div class="label">操作结果</div>` +
            `<div class="demand-text">userId=${escapeHtml(data.userId)}：${escapeHtml(data.message)}</div>` +
            `<div>可重新执行「测试1：首次存偏好」验证记忆写入。</div>`;
    } catch (e) {
        resultBox.className = 'memory-answer error';
        resultBox.textContent = '清除记忆失败：' + e.message;
    } finally {
        btn.disabled = false;
    }
}

async function planTripWithMysqlMemory() {
    const userId = document.getElementById('mysqlMemoryUserIdInput').value.trim();
    const memoryType = document.getElementById('mysqlMemoryTypeSelect').value;
    const demand = document.getElementById('mysqlMemoryDemandInput').value.trim();
    const resultBox = document.getElementById('mysqlMemoryResult');
    const btn = document.getElementById('mysqlMemoryPlanBtn');

    if (!userId) { alert('请输入用户 ID'); return; }
    if (!demand) { alert('请输入出行需求'); return; }

    btn.disabled = true;
    resultBox.className = 'mysql-memory-answer loading';
    resultBox.innerHTML = `正在使用 ${escapeHtml(memoryType)} 模式读取 MySQL 记忆并规划行程（DeepSeek 推理中）...`;

    try {
        const url = `/agent/mysql/trip/plan?userId=${encodeURIComponent(userId)}&memoryType=${encodeURIComponent(memoryType)}&demand=${encodeURIComponent(demand)}`;
        const res = await fetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const result = await res.json();
        const data = result.data || {};

        resultBox.className = 'mysql-memory-answer';
        resultBox.innerHTML =
            `<div class="label">用户 ID</div>` +
            `<div class="demand-text">${escapeHtml(data.userId || userId)}</div>` +
            `<div class="label">你的出行需求</div>` +
            `<div class="demand-text">${escapeHtml(data.userDemand || demand)}</div>` +
            `<span class="mysql-badge">${escapeHtml(data.storageType || 'MySQL 持久化')}</span>` +
            `<span class="mysql-badge">memoryType=${escapeHtml(data.memoryType || memoryType)}</span>` +
            `<div class="label">DB 持久化记忆行程规划结果（DeepSeek 生成）</div>` +
            `<div>${escapeHtml(data.tripPlan || '')}</div>`;
    } catch (e) {
        resultBox.className = 'mysql-memory-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认：\n1. MySQL 已启动\n2. 已创建 spring_ai_agent2 数据库\n3. application.properties 中数据库用户名/密码正确\n4. DeepSeek API Key 已配置';
    } finally {
        btn.disabled = false;
    }
}

async function clearMysqlMemory() {
    const userId = document.getElementById('mysqlMemoryUserIdInput').value.trim();
    const resultBox = document.getElementById('mysqlMemoryResult');
    const btn = document.getElementById('mysqlMemoryClearBtn');

    if (!userId) { alert('请输入用户 ID'); return; }

    btn.disabled = true;
    try {
        const res = await fetch(`/agent/mysql/trip/clear-memory?userId=${encodeURIComponent(userId)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const result = await res.json();
        const data = result.data || {};

        resultBox.className = 'mysql-memory-answer';
        resultBox.innerHTML =
            `<div class="label">操作结果</div>` +
            `<div class="demand-text">userId=${escapeHtml(data.userId || userId)}：${escapeHtml(result.msg || '用户 MySQL 记忆清除成功')}</div>` +
            `<div>可重新执行「测试1：首次写入 MySQL」验证记忆重新写入。</div>`;
    } catch (e) {
        resultBox.className = 'mysql-memory-answer error';
        resultBox.textContent = '清除 DB 记忆失败：' + e.message;
    } finally {
        btn.disabled = false;
    }
}

async function listMysqlConversations() {
    const resultBox = document.getElementById('mysqlMemoryResult');
    const btn = document.getElementById('mysqlMemoryListBtn');

    btn.disabled = true;
    try {
        const res = await fetch('/agent/mysql/trip/list-conversations');
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const result = await res.json();
        const data = result.data || {};
        const ids = data.conversationIds || [];

        resultBox.className = 'mysql-memory-answer';
        resultBox.innerHTML =
            `<div class="label">MySQL 会话统计</div>` +
            `<span class="mysql-badge">conversationCount=${escapeHtml(String(data.conversationCount || 0))}</span>` +
            `<div>${escapeHtml(ids.length ? ids.join('\n') : '暂无会话 ID，请先执行一次 DB 记忆规划。')}</div>`;
    } catch (e) {
        resultBox.className = 'mysql-memory-answer error';
        resultBox.textContent = '查询会话 ID 失败：' + e.message;
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('memoryDemandInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); planTripWithMemory(); }
});

document.getElementById('mysqlMemoryDemandInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); planTripWithMysqlMemory(); }
});
