// ========== 订单超时取消 Demo ==========
let orderDelayLastOrderId = '';
let orderDelayLastTaskId = '';

function orderDelayAppend(msg) {
    const box = document.getElementById('orderDelayLog');
    const line = '[' + new Date().toLocaleTimeString() + '] ' + msg;
    box.className = 'result-box order-delay-log';
    box.textContent = (box.textContent && !box.textContent.startsWith('操作日志')
        ? box.textContent + '\n'
        : '') + line;
}

async function orderDelayJsonPost(url, body) {
    return fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
}

async function orderDelayCreate() {
    const amount = document.getElementById('orderDelayAmount').value.trim();
    const delay = document.getElementById('orderDelaySeconds').value.trim() || '10s';
    const resultBox = document.getElementById('orderDelayResult');
    resultBox.className = 'result-box loading';
    resultBox.textContent = '创建订单中...';
    try {
        const res = await orderDelayJsonPost('/demo/orders/orderPlace', {
            amount: Number(amount),
            delay: delay
        });
        const text = await res.text();
        const data = JSON.parse(text);
        if (!res.ok) {
            resultBox.className = 'result-box error';
            resultBox.textContent = '创建失败：' + text;
            return;
        }
        orderDelayLastOrderId = String(data.orderId);
        orderDelayLastTaskId = String(data.taskId);
        document.getElementById('orderDelayOrderId').value = orderDelayLastOrderId;
        resultBox.className = 'result-box';
        resultBox.textContent = JSON.stringify(data, null, 2);
        orderDelayAppend('已创建 orderId=' + orderDelayLastOrderId + ' taskId=' + orderDelayLastTaskId
            + ' delay=' + delay + '（到期后点「刷新订单」看是否 CANCELLED）');
    } catch (e) {
        resultBox.className = 'result-box error';
        resultBox.textContent = '请求失败：' + e.message;
    }
}

async function orderDelayPay() {
    const orderId = document.getElementById('orderDelayOrderId').value.trim() || orderDelayLastOrderId;
    if (!orderId) {
        alert('请先创建订单或填写 orderId');
        return;
    }
    const resultBox = document.getElementById('orderDelayResult');
    resultBox.className = 'result-box loading';
    resultBox.textContent = '支付中...';
    try {
        const res = await orderDelayJsonPost('/demo/orders/pay', { orderId: orderId });
        const text = await res.text();
        if (!res.ok) {
            resultBox.className = 'result-box error';
            resultBox.textContent = '支付失败：' + text;
            return;
        }
        resultBox.className = 'result-box';
        resultBox.textContent = text;
        orderDelayAppend('已支付 orderId=' + orderId + '（到期后应仍为 PAID）');
    } catch (e) {
        resultBox.className = 'result-box error';
        resultBox.textContent = '请求失败：' + e.message;
    }
}

async function orderDelayCancel() {
    const orderId = document.getElementById('orderDelayOrderId').value.trim() || orderDelayLastOrderId;
    if (!orderId) {
        alert('请先创建订单或填写 orderId');
        return;
    }
    const resultBox = document.getElementById('orderDelayResult');
    resultBox.className = 'result-box loading';
    resultBox.textContent = '取消中...';
    try {
        const res = await orderDelayJsonPost('/demo/orders/cancel', { orderId: orderId });
        const text = await res.text();
        if (!res.ok) {
            resultBox.className = 'result-box error';
            resultBox.textContent = '取消失败：' + text;
            return;
        }
        resultBox.className = 'result-box';
        resultBox.textContent = text;
        orderDelayAppend('已取消 orderId=' + orderId + '（延时任务应被逻辑取消）');
    } catch (e) {
        resultBox.className = 'result-box error';
        resultBox.textContent = '请求失败：' + e.message;
    }
}

async function orderDelayRefresh() {
    const orderId = document.getElementById('orderDelayOrderId').value.trim() || orderDelayLastOrderId;
    if (!orderId) {
        alert('请填写 orderId');
        return;
    }
    const resultBox = document.getElementById('orderDelayResult');
    resultBox.className = 'result-box loading';
    resultBox.textContent = '查询中...';
    try {
        const [orderRes, taskRes] = await Promise.all([
            orderDelayJsonPost('/demo/orders/get', { orderId: orderId }),
            fetch('/demo/delay-tasks?bizKey=' + encodeURIComponent(orderId))
        ]);
        const orderText = await orderRes.text();
        const taskText = await taskRes.text();
        resultBox.className = orderRes.ok ? 'result-box' : 'result-box error';
        resultBox.textContent = '订单：\n' + orderText + '\n\n台账：\n' + taskText;
        orderDelayAppend('刷新 orderId=' + orderId);
    } catch (e) {
        resultBox.className = 'result-box error';
        resultBox.textContent = '请求失败：' + e.message;
    }
}
