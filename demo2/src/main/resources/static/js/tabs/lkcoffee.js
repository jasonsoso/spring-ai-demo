// ========== 瑞幸 MCP 点单 ==========
let lkCoffeeSessionId = crypto.randomUUID();
const LK_TOKEN_KEY = 'lkcoffee_token';

function initLkCoffeeToken() {
    const saved = sessionStorage.getItem(LK_TOKEN_KEY);
    if (saved) {
        document.getElementById('lkCoffeeTokenInput').value = saved;
    }
}

function saveLkCoffeeToken() {
    const token = document.getElementById('lkCoffeeTokenInput').value.trim();
    if (token) {
        sessionStorage.setItem(LK_TOKEN_KEY, token);
        alert('Token 已保存到 sessionStorage');
    } else {
        sessionStorage.removeItem(LK_TOKEN_KEY);
        alert('Token 已清除');
    }
}

function initLkCoffeeLocation() {
    const statusEl = document.getElementById('lkCoffeeLocStatus');
    if (!navigator.geolocation) {
        statusEl.textContent = '浏览器不支持定位，请手动输入或地址解析';
        return;
    }
    statusEl.textContent = '正在获取定位...';
    navigator.geolocation.getCurrentPosition(
        pos => {
            document.getElementById('lkCoffeeLongitude').value = pos.coords.longitude.toFixed(6);
            document.getElementById('lkCoffeeLatitude').value = pos.coords.latitude.toFixed(6);
            statusEl.textContent = '定位成功';
        },
        () => {
            statusEl.textContent = '定位失败，请手动输入经纬度或地址解析';
        },
        { enableHighAccuracy: true, timeout: 10000 }
    );
}

async function geocodeLkCoffeeAddress() {
    const address = document.getElementById('lkCoffeeAddressInput').value.trim();
    if (!address) {
        alert('请输入地址');
        return;
    }
    const statusEl = document.getElementById('lkCoffeeLocStatus');
    statusEl.textContent = '地址解析中...';
    try {
        const res = await fetch('/agent/lkcoffee/geocode?address=' + encodeURIComponent(address));
        if (!res.ok) throw new Error(await res.text() || 'HTTP ' + res.status);
        const data = await res.json();
        statusEl.textContent = '解析结果：' + (data.raw || JSON.stringify(data));
    } catch (e) {
        statusEl.textContent = '解析失败：' + e.message;
    }
}

function fillLkCoffeeMessage(text) {
    document.getElementById('lkCoffeeMessageInput').value = text;
    document.getElementById('lkCoffeeMessageInput').focus();
}

function setLkCoffeeInputEnabled(enabled) {
    document.getElementById('lkCoffeeMessageInput').disabled = !enabled;
    document.getElementById('lkCoffeeSendBtn').disabled = !enabled;
}

function scrollLkCoffeeMessages() {
    const box = document.getElementById('lkCoffeeMessages');
    box.scrollTop = box.scrollHeight;
}

function appendLkCoffeeBubble(text, isUser) {
    const box = document.getElementById('lkCoffeeMessages');
    const welcome = document.getElementById('lkCoffeeWelcome');
    if (welcome) welcome.remove();

    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    if (isUser) {
        content.textContent = text;
    } else {
        content.innerHTML = '<div class="tool-tags"></div><div class="order-preview-area"></div><div class="payment-qr-area"></div><div class="response-text"></div>';
    }
    div.appendChild(content);
    box.appendChild(div);
    scrollLkCoffeeMessages();
    return content;
}

async function sendLkCoffeeMessage() {
    const message = document.getElementById('lkCoffeeMessageInput').value.trim();
    if (!message) return;

    document.getElementById('lkCoffeeMessageInput').value = '';
    appendLkCoffeeBubble(message, true);
    const assistantContent = appendLkCoffeeBubble('', false);
    const toolTags = assistantContent.querySelector('.tool-tags');
    const previewArea = assistantContent.querySelector('.order-preview-area');
    const qrArea = assistantContent.querySelector('.payment-qr-area');
    const responseText = assistantContent.querySelector('.response-text');
    setLkCoffeeInputEnabled(false);

    const token = sessionStorage.getItem(LK_TOKEN_KEY) || undefined;
    const lonVal = document.getElementById('lkCoffeeLongitude').value;
    const latVal = document.getElementById('lkCoffeeLatitude').value;
    const body = {
        sessionId: lkCoffeeSessionId,
        message: message,
        token: token || undefined,
        longitude: lonVal ? parseFloat(lonVal) : undefined,
        latitude: latVal ? parseFloat(latVal) : undefined
    };

    try {
        const response = await fetch('/agent/lkcoffee/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (!response.ok) throw new Error(await response.text() || 'HTTP ' + response.status);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';
            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed.startsWith('data:')) continue;
                const json = trimmed.replace(/^data:\s*/, '');
                if (!json) continue;
                const evt = JSON.parse(json);
                if (evt.type === 'TOOL_CALL') {
                    const tag = document.createElement('span');
                    tag.className = 'lkcoffee-tool-tag';
                    tag.textContent = '🔧 ' + (evt.toolName || 'tool');
                    toolTags.appendChild(tag);
                } else if (evt.type === 'ORDER_PREVIEW') {
                    const card = document.createElement('div');
                    card.className = 'order-preview-card';
                    card.textContent = typeof evt.payload === 'string' ? evt.payload : JSON.stringify(evt.payload, null, 2);
                    previewArea.appendChild(card);
                } else if (evt.type === 'PAYMENT_QR' && evt.qrUrl) {
                    qrArea.innerHTML = '<div class="payment-qr"><p>请扫码支付：</p><img src="' + escapeHtml(evt.qrUrl) + '" alt="支付二维码"></div>';
                } else if (evt.type === 'TOKEN' && evt.content) {
                    responseText.textContent += evt.content;
                    scrollLkCoffeeMessages();
                } else if (evt.type === 'FAILED') {
                    throw new Error(evt.error || 'Agent 失败');
                }
            }
        }
    } catch (e) {
        responseText.textContent = '错误：' + e.message;
        assistantContent.parentElement.classList.add('error');
    } finally {
        setLkCoffeeInputEnabled(true);
        document.getElementById('lkCoffeeMessageInput').focus();
    }
}

async function clearLkCoffeeSession() {
    if (!confirm('确认清除当前 sessionId 的对话记忆？')) return;
    try {
        const res = await fetch('/agent/lkcoffee/clear?sessionId=' + encodeURIComponent(lkCoffeeSessionId), { method: 'DELETE' });
        if (!res.ok) throw new Error(await res.text() || 'HTTP ' + res.status);
        document.getElementById('lkCoffeeMessages').innerHTML =
            '<div id="lkCoffeeWelcome" class="message assistant"><div class="message-content">' +
            '会话已清除。配置 Token 与定位后，用自然语言点一杯咖啡吧。</div></div>';
        lkCoffeeSessionId = crypto.randomUUID();
        document.getElementById('lkCoffeeSessionIdDisplay').textContent = lkCoffeeSessionId;
    } catch (e) {
        alert('清除失败：' + e.message);
    }
}

document.getElementById('lkCoffeeForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendLkCoffeeMessage();
});

document.addEventListener('DOMContentLoaded', function () {
    initLkCoffeeToken();
    initLkCoffeeLocation();
    const el = document.getElementById('lkCoffeeSessionIdDisplay');
    if (el) el.textContent = lkCoffeeSessionId;
});
