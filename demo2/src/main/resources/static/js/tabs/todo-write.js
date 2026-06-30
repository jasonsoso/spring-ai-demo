// ========== TodoWrite 学习计划 ==========
let todoEventSource = null;

function fillTodoMessage(text) {
    document.getElementById('todoMessageInput').value = text;
}

function closeTodoEventSource() {
    if (todoEventSource) {
        todoEventSource.close();
        todoEventSource = null;
    }
}

function renderTodoBoard(todos, progress) {
    const board = document.getElementById('todoBoard');
    const list = document.getElementById('todoList');
    const progressEl = document.getElementById('todoProgress');
    board.style.display = 'block';
    if (progress) {
        progressEl.textContent = progress.completed + '/' + progress.total + ' (' + progress.percent + '%)';
    }
    list.innerHTML = (todos || []).map(t => {
        const icon = t.status === 'completed' ? '[✓]' : t.status === 'in_progress' ? '[→]' : '[ ]';
        const style = t.status === 'in_progress' ? 'color:#0f3443;font-weight:600;' : t.status === 'completed' ? 'color:#888;text-decoration:line-through;' : '';
        return '<div style="' + style + '">' + icon + ' ' + escapeHtml(t.content) + '</div>';
    }).join('');
}

function initTodoResultArea() {
    const resultBox = document.getElementById('todoResult');
    resultBox.dataset.stepsStarted = '1';
    resultBox.innerHTML =
        '<div class="label">📝 分步产出（每完成一项子任务推送一次）</div>' +
        '<div id="todoStepResults"></div>' +
        '<div id="todoFinalResult" class="todo-final-block" style="display:none;"></div>';
}

function appendTodoTaskResult(payload) {
    const resultBox = document.getElementById('todoResult');
    if (!resultBox.dataset.stepsStarted) {
        initTodoResultArea();
    }
    resultBox.className = 'agent-tools-answer';
    const steps = document.getElementById('todoStepResults');
    const idx = payload.taskIndex || steps.children.length + 1;
    const total = payload.totalTasks ? ' / ' + payload.totalTasks : '';
    const name = payload.taskName || '子任务';
    const block = document.createElement('div');
    block.className = 'todo-step-block';
    block.innerHTML =
        '<div class="todo-step-title">✓ 任务 ' + idx + total + '：' + escapeHtml(name) + '</div>' +
        '<div class="plan-text">' + escapeHtml(payload.response || '') + '</div>';
    steps.appendChild(block);
    block.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function renderTodoFinalResult(content) {
    const resultBox = document.getElementById('todoResult');
    const finalHtml =
        '<div class="label">📚 最终学习计划（整合版）</div>' +
        '<div class="plan-text">' + escapeHtml(content || '') + '</div>';
    if (resultBox.dataset.stepsStarted) {
        const finalBox = document.getElementById('todoFinalResult');
        finalBox.style.display = 'block';
        finalBox.innerHTML = finalHtml;
        finalBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    } else {
        resultBox.innerHTML = finalHtml;
    }
    resultBox.className = 'agent-tools-answer';
}

async function startTodoChat() {
    const message = document.getElementById('todoMessageInput').value.trim();
    if (!message) return;

    closeTodoEventSource();
    document.getElementById('todoBoard').style.display = 'none';
    document.getElementById('todoList').innerHTML = '';
    document.getElementById('todoProgress').textContent = '';
    const resultBox = document.getElementById('todoResult');
    delete resultBox.dataset.stepsStarted;
    resultBox.className = 'agent-tools-answer loading';
    resultBox.textContent = 'Agent 正在规划...';

    const btn = document.getElementById('todoStartBtn');
    btn.disabled = true;

    try {
        const resp = await fetch('/agent/todo/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
        });
        const data = await resp.json();

        todoEventSource = new EventSource('/agent/todo/sse/' + data.sessionId);
        todoEventSource.onmessage = (event) => {
            handleTodoSseEvent(JSON.parse(event.data));
        };
        todoEventSource.onerror = () => {
            resultBox.className = 'agent-tools-answer error';
            resultBox.textContent = 'SSE 连接中断';
            closeTodoEventSource();
            btn.disabled = false;
        };
    } catch (e) {
        resultBox.className = 'agent-tools-answer error';
        resultBox.textContent = '请求失败: ' + e.message;
        btn.disabled = false;
    }
}

function handleTodoSseEvent(payload) {
    const resultBox = document.getElementById('todoResult');
    if (payload.type === 'RUNNING') {
        resultBox.className = 'agent-tools-answer loading';
        resultBox.textContent = 'Agent 正在拆解学习任务...';
    } else if (payload.type === 'TODOS') {
        renderTodoBoard(payload.todos, payload.progress);
    } else if (payload.type === 'TASK_RESULT') {
        appendTodoTaskResult(payload);
    } else if (payload.type === 'COMPLETED') {
        renderTodoFinalResult(payload.response || '');
        closeTodoEventSource();
        document.getElementById('todoStartBtn').disabled = false;
    } else if (payload.type === 'FAILED') {
        resultBox.className = 'agent-tools-answer error';
        resultBox.textContent = payload.error || '未知错误';
        closeTodoEventSource();
        document.getElementById('todoStartBtn').disabled = false;
    }
}

window.addEventListener('beforeunload', closeTodoEventSource);
document.getElementById('todoMessageInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); startTodoChat(); }
});
