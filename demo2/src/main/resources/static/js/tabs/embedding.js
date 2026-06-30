// ========== Embedding 功能 ==========
async function findSimilarity() {
    const query = document.getElementById('queryInput').value.trim();
    const algorithm = document.getElementById('algorithmSelect').value;
    const resultBox = document.getElementById('similarityResult');
    const btn = document.getElementById('similarityBtn');

    if (!query) { alert('请输入查询文本'); return; }

    btn.disabled = true;
    resultBox.className = 'result-box loading';
    resultBox.textContent = '正在调用智普AI向量化接口...';

    try {
        const res = await fetch(`/ai/similarity?query=${encodeURIComponent(query)}&algorithm=${algorithm}`);
        const data = await res.json();

        if (data.error) {
            resultBox.className = 'result-box error';
            resultBox.textContent = '错误：' + data.error;
        } else {
            resultBox.className = 'result-box';
            resultBox.textContent =
                `查询文本：${data.query}\n` +
                `使用算法：${data.algorithm}\n` +
                `最相似结果：\n${data.answer}`;
        }
    } catch (e) {
        resultBox.className = 'result-box error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请检查智普AI API Key是否已配置（application.properties）';
    } finally {
        btn.disabled = false;
    }
}

async function getEmbedding() {
    const message = document.getElementById('embeddingInput').value.trim();
    const resultBox = document.getElementById('embeddingResult');
    const btn = document.getElementById('embeddingBtn');

    if (!message) { alert('请输入文本'); return; }

    btn.disabled = true;
    resultBox.className = 'result-box loading';
    resultBox.textContent = '正在向量化...';

    try {
        const res = await fetch(`/ai/embedding?message=${encodeURIComponent(message)}`);
        const data = await res.json();
        resultBox.className = 'result-box';
        resultBox.textContent =
            `文本：${data.message}\n` +
            `向量维度：${data.vectorDimension}\n` +
            `向量（前10维）：[${Array.from(data.vector).slice(0, 10).map(v => v.toFixed(4)).join(', ')}, ...]`;
    } catch (e) {
        resultBox.className = 'result-box error';
        resultBox.textContent = '请求失败：' + e.message + '\n\n请检查智普AI API Key是否已配置（application.properties）';
    } finally {
        btn.disabled = false;
    }
}
