// ========== Agent 自主记忆（AutoMemoryTools） ==========
const AUTO_MEMORY_TEST_CASES = {
    1: {
        userId: '1001',
        message: '周末两天杭州游，2人，偏好西湖人文景点，素食，地铁出行'
    },
    2: {
        userId: '1001',
        message: '下周三天，再规划一次杭州周边游'
    },
    3: {
        userId: '1002',
        message: '周末一天苏州游，1人，园林景点，不吃辣，高铁+地铁'
    }
};

function fillAutoMemoryTest(testNo) {
    const testCase = AUTO_MEMORY_TEST_CASES[testNo];
    if (!testCase) return;
    document.getElementById('autoMemoryUserIdInput').value = testCase.userId;
    document.getElementById('autoMemoryMessageInput').value = testCase.message;
    document.getElementById('autoMemoryMessageInput').focus();
}

async function sendAutoMemoryChat() {
    const userId = document.getElementById('autoMemoryUserIdInput').value.trim();
    const message = document.getElementById('autoMemoryMessageInput').value.trim();
    const resultBox = document.getElementById('autoMemoryResult');
    const btn = document.getElementById('autoMemorySendBtn');

    if (!userId) { alert('请输入用户 ID'); return; }
    if (!message) { alert('请输入消息'); return; }

    btn.disabled = true;
    resultBox.className = 'auto-memory-answer loading';
    resultBox.innerHTML = `正在为 userId=${escapeHtml(userId)} 加载双层记忆并规划行程（DeepSeek 推理中）...`;

    try {
        const res = await fetch('/agent/auto-memory/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, message })
        });
        if (!res.ok) {
            const errText = await res.text();
            throw new Error(errText || `HTTP ${res.status}`);
        }
        const data = await res.json();

        resultBox.className = 'auto-memory-answer';
        resultBox.innerHTML =
            `<div class="label">用户 ID</div>` +
            `<div class="demand-text">${escapeHtml(data.userId)}</div>` +
            `<div class="label">你的消息</div>` +
            `<div class="demand-text">${escapeHtml(data.message)}</div>` +
            `<span class="auto-memory-badge">${escapeHtml(data.agentType)}</span>` +
            `<div class="label">Agent 回复</div>` +
            `<div>${escapeHtml(data.reply)}</div>`;

        await refreshAutoMemoryFiles();
    } catch (e) {
        resultBox.className = 'auto-memory-answer error';
        resultBox.textContent = '请求失败：' + e.message;
    } finally {
        btn.disabled = false;
    }
}

async function refreshAutoMemoryFiles() {
    const userId = document.getElementById('autoMemoryUserIdInput').value.trim();
    const listBox = document.getElementById('autoMemoryFileList');
    const btn = document.getElementById('autoMemoryRefreshBtn');

    if (!userId) { alert('请输入用户 ID'); return; }

    btn.disabled = true;
    listBox.textContent = '正在加载记忆文件列表...';

    try {
        const res = await fetch(`/agent/auto-memory/memories?userId=${encodeURIComponent(userId)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        const files = data.files || [];

        if (files.length === 0) {
            listBox.innerHTML = `<div>目录 ${escapeHtml(data.memoriesRoot || '')} 暂无 .md 记忆文件</div>`;
            return;
        }

        listBox.innerHTML = files.map(f =>
            `<div class="file-item">` +
            `<strong>${escapeHtml(f.name)}</strong> ` +
            `(${escapeHtml(f.relativePath)}, ${f.sizeBytes} bytes)` +
            `</div>`
        ).join('');
    } catch (e) {
        listBox.className = 'auto-memory-file-list error';
        listBox.textContent = '加载失败：' + e.message;
    } finally {
        btn.disabled = false;
    }
}

async function clearAutoMemory() {
    const userId = document.getElementById('autoMemoryUserIdInput').value.trim();
    const resultBox = document.getElementById('autoMemoryResult');
    const btn = document.getElementById('autoMemoryClearBtn');

    if (!userId) { alert('请输入用户 ID'); return; }
    if (!confirm(`确定清除 userId=${userId} 的 MySQL 短期记忆与 Markdown 长期记忆吗？`)) return;

    btn.disabled = true;
    try {
        const res = await fetch(`/agent/auto-memory/clear-memory?userId=${encodeURIComponent(userId)}`, {
            method: 'DELETE'
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        resultBox.className = 'auto-memory-answer';
        resultBox.innerHTML =
            `<div class="label">清除结果</div>` +
            `<div>${escapeHtml(data.message)} (userId=${escapeHtml(data.userId)})</div>`;
        document.getElementById('autoMemoryFileList').textContent = '记忆文件已清除';
    } catch (e) {
        resultBox.className = 'auto-memory-answer error';
        resultBox.textContent = '清除失败：' + e.message;
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('autoMemoryMessageInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendAutoMemoryChat();
    }
});
