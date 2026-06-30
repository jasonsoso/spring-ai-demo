// ========== RAG 功能 ==========
function fillRagQuestion(question) {
    document.getElementById('ragQuestionInput').value = question;
    document.getElementById('ragQuestionInput').focus();
}

async function askRag() {
    const question = document.getElementById('ragQuestionInput').value.trim();
    const resultBox = document.getElementById('ragResult');
    const btn = document.getElementById('ragAskBtn');

    if (!question) { alert('请输入问题'); return; }

    btn.disabled = true;
    resultBox.className = 'rag-answer loading';
    resultBox.innerHTML = '正在检索知识库并生成回答，请稍候（首次请求需向量化，约 10~15 秒）...';

    try {
        const res = await fetch(`/rag/ask?question=${encodeURIComponent(question)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        resultBox.className = 'rag-answer';
        resultBox.innerHTML =
            `<div class="label">你的问题</div>` +
            `<div class="question-text">${escapeHtml(data.question)}</div>` +
            `<div class="label">RAG 回答（基于知识库检索 + DeepSeek 生成）</div>` +
            `<div>${escapeHtml(data.answer)}</div>`;
    } catch (e) {
        resultBox.className = 'rag-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认服务已启动，且智谱 Embedding / DeepSeek API Key 已配置。';
    } finally {
        btn.disabled = false;
    }
}
document.getElementById('ragQuestionInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); askRag(); }
});

// ========== RAG 优化版 (Milvus) ==========
function fillRagOptQuestion(question) {
    document.getElementById('ragOptQuestionInput').value = question;
    document.getElementById('ragOptQuestionInput').focus();
}

async function askRagOptimized() {
    const question = document.getElementById('ragOptQuestionInput').value.trim();
    const resultBox = document.getElementById('ragOptResult');
    const btn = document.getElementById('ragOptAskBtn');

    if (!question) { alert('请输入问题'); return; }

    btn.disabled = true;
    resultBox.className = 'rag-answer loading';
    resultBox.innerHTML = '正在通过 Milvus 检索知识库并生成回答，请稍候...';

    try {
        const res = await fetch(`/rag/optimized/ask?question=${encodeURIComponent(question)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        resultBox.className = 'rag-answer';
        resultBox.innerHTML =
            `<div class="label">你的问题</div>` +
            `<div class="question-text">${escapeHtml(data.question)}</div>` +
            `<div class="label">优化版 RAG 回答（Milvus 检索 + DeepSeek 生成）</div>` +
            `<div>${escapeHtml(data.answer)}</div>`;
    } catch (e) {
        resultBox.className = 'rag-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认 Milvus 已运行（localhost:19530），且智谱 Embedding / DeepSeek API Key 已配置。';
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('ragOptQuestionInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); askRagOptimized(); }
});
