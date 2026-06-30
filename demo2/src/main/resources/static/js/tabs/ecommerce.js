// ========== 电商客服 RAG ==========
let ecommerceMode = 'precise';

function selectEcommerceMode(mode) {
    ecommerceMode = mode;
    document.getElementById('modeLabel-precise').classList.toggle('selected', mode === 'precise');
    document.getElementById('modeLabel-enhanced').classList.toggle('selected', mode === 'enhanced');
}

function fillEcommerceQuestion(question) {
    document.getElementById('ecommerceQuestionInput').value = question;
    document.getElementById('ecommerceQuestionInput').focus();
}

async function askEcommerce() {
    const question = document.getElementById('ecommerceQuestionInput').value.trim();
    const resultBox = document.getElementById('ecommerceResult');
    const btn = document.getElementById('ecommerceAskBtn');

    if (!question) { alert('请输入问题'); return; }

    btn.disabled = true;
    resultBox.className = 'ecommerce-answer loading';
    const modeText = ecommerceMode === 'precise' ? '精准条款查询' : '复杂场景增强';
    resultBox.innerHTML = `正在使用【${modeText}】模式检索知识库并生成回答，请稍候...`;

    const endpoint = ecommerceMode === 'precise'
        ? `/ecommerce/service/chat/precise?question=${encodeURIComponent(question)}`
        : `/ecommerce/service/chat/enhanced?question=${encodeURIComponent(question)}`;

    try {
        const res = await fetch(endpoint);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        const modeBadgeClass = ecommerceMode === 'precise' ? 'precise' : 'enhanced';
        resultBox.className = 'ecommerce-answer';
        resultBox.innerHTML =
            `<div class="label">你的问题</div>` +
            `<div class="question-text">${escapeHtml(data.question)}</div>` +
            `<span class="mode-badge ${modeBadgeClass}">${escapeHtml(data.mode)}</span>` +
            `<div class="label">电商客服回答（智谱 Embedding 检索 + DeepSeek 生成）</div>` +
            `<div>${escapeHtml(data.answer)}</div>`;
    } catch (e) {
        resultBox.className = 'ecommerce-answer error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请确认：\n1. Milvus 已运行（localhost:19530）\n2. 智谱 Embedding API Key 已配置\n3. DeepSeek API Key 已配置\n4. 已将 ecommerce.reindex-on-startup=true 首次运行入库';
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('ecommerceQuestionInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); askEcommerce(); }
});
