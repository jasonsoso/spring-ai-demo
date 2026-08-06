// ========== RocketMQ Demo ==========
async function rocketmqSend(mode) {
    const orderId = document.getElementById('mqOrderId').value.trim();
    const type = document.getElementById('mqType').value.trim() || 'CREATED';
    const payload = document.getElementById('mqPayload').value.trim();
    const level = document.getElementById('mqDelayLevel').value;
    const resultBox = document.getElementById('mqSendResult');

    if (!orderId) {
        alert('请填写 orderId');
        return;
    }

    let url = '/demo/mq/orders/' + mode;
    if (mode === 'delay') {
        url += '?level=' + encodeURIComponent(level);
    }

    resultBox.className = 'result-box loading';
    resultBox.textContent = '发送中 (' + mode + ')...';

    try {
        const res = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ orderId, type, payload })
        });
        const text = await res.text();
        let data;
        try {
            data = JSON.parse(text);
        } catch (e) {
            throw new Error(text || ('HTTP ' + res.status));
        }
        if (!res.ok) {
            resultBox.className = 'result-box error';
            resultBox.textContent = '发送失败：' + JSON.stringify(data, null, 2);
            return;
        }
        resultBox.className = 'result-box';
        resultBox.textContent = JSON.stringify(data, null, 2);
        // 给消费一点时间后自动刷新
        setTimeout(rocketmqRefreshEvents, mode === 'delay' ? 2500 : 800);
    } catch (e) {
        resultBox.className = 'result-box error';
        resultBox.textContent = '请求失败：' + e.message;
    }
}

async function rocketmqRefreshEvents() {
    const orderId = document.getElementById('mqFilterOrderId').value.trim();
    const box = document.getElementById('mqEventsResult');
    box.className = 'result-box loading rocketmq-events';
    box.textContent = '加载消费结果...';

    try {
        let url = '/demo/mq/orders/events';
        if (orderId) {
            url += '?orderId=' + encodeURIComponent(orderId);
        }
        const res = await fetch(url);
        const data = await res.json();
        box.className = 'result-box rocketmq-events';
        if (!Array.isArray(data) || data.length === 0) {
            box.textContent = '暂无消费记录（可稍后点「刷新」）';
            return;
        }
        box.textContent = data.map((item, idx) => {
            const ev = item.event || {};
            return [
                '#' + (idx + 1),
                'channel=' + item.channel,
                'orderId=' + (ev.orderId || ''),
                'type=' + (ev.type || ''),
                'payload=' + (ev.payload || ''),
                'createdAt=' + (ev.createdAt || '')
            ].join(' | ');
        }).join('\n');
    } catch (e) {
        box.className = 'result-box error rocketmq-events';
        box.textContent = '加载失败：' + e.message;
    }
}

async function rocketmqClearEvents() {
    const box = document.getElementById('mqEventsResult');
    try {
        const res = await fetch('/demo/mq/orders/events', { method: 'DELETE' });
        const data = await res.json();
        box.className = 'result-box rocketmq-events';
        box.textContent = '已清空：' + JSON.stringify(data);
    } catch (e) {
        box.className = 'result-box error rocketmq-events';
        box.textContent = '清空失败：' + e.message;
    }
}
