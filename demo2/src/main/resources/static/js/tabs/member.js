// ========== 会员 C 端 Demo ==========
const MEMBER_TOKEN_STORAGE_KEY = 'demo2MemberToken';
let memberToken = localStorage.getItem(MEMBER_TOKEN_STORAGE_KEY) || '';
let memberProfile = null;
let memberMobileTab = 'home';
let memberSessionDeleted = false;
let memberOrderLastOrderId = '';
let memberOrderLastTaskId = '';

function memberDefaultAvatar() {
    return 'data:image/svg+xml;utf8,' + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96">' +
        '<rect width="96" height="96" rx="48" fill="#e5e7eb"/>' +
        '<circle cx="48" cy="36" r="16" fill="#94a3b8"/>' +
        '<path d="M20 82c6-18 50-18 56 0" fill="#94a3b8"/></svg>'
    );
}

function memberEscapeHtml(value) {
    return String(value == null ? '' : value)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function memberAvatarUrl(value) {
    if (!value) {
        return memberDefaultAvatar();
    }
    try {
        const url = new URL(value, window.location.origin);
        return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : memberDefaultAvatar();
    } catch (e) {
        return memberDefaultAvatar();
    }
}

function memberAppendLog(message) {
    const box = document.getElementById('memberLog');
    if (!box) {
        return;
    }
    const line = '[' + new Date().toLocaleTimeString() + '] ' + message;
    box.textContent = (box.textContent && !box.textContent.startsWith('操作日志')
        ? box.textContent + '\n'
        : '') + line;
    box.scrollTop = box.scrollHeight;
}

function memberHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (memberToken) {
        headers.Authorization = 'Bearer ' + memberToken;
    }
    return headers;
}

async function memberPost(url, body) {
    return fetch(url, {
        method: 'POST',
        headers: memberHeaders(),
        body: JSON.stringify(body || {})
    });
}

function memberSwitchMobileTab(tab) {
    if (!['home', 'orders', 'me'].includes(tab)) {
        return;
    }
    memberMobileTab = tab;
    memberRender();
}

function memberRender() {
    const page = document.getElementById('memberPhonePage');
    if (!page) {
        return;
    }
    document.getElementById('memberNavHome').classList.toggle('active', memberMobileTab === 'home');
    document.getElementById('memberNavOrders').classList.toggle('active', memberMobileTab === 'orders');
    document.getElementById('memberNavMe').classList.toggle('active', memberMobileTab === 'me');
    if (memberMobileTab === 'home') {
        memberRenderHome();
    } else if (memberMobileTab === 'orders') {
        memberRenderOrders();
    } else {
        memberRenderMe();
    }
    memberRenderSession();
}

function memberRenderHome() {
    document.getElementById('memberPhonePage').innerHTML =
        '<h2>首页</h2>' +
        '<div class="member-home-banner"><strong>今日精选</strong><span>静态商品演示 · 右侧面板可下单/支付/取消</span></div>' +
        '<div class="member-products">' +
        '<div class="member-product-card"><span class="member-product-icon">☕</span><div><strong>拿铁</strong><p>经典浓郁，口感顺滑</p></div><span class="member-product-price">¥18</span></div>' +
        '<div class="member-product-card"><span class="member-product-icon">🥥</span><div><strong>生椰拿铁</strong><p>椰香清甜，清爽不腻</p></div><span class="member-product-price">¥20</span></div>' +
        '<div class="member-product-card"><span class="member-product-icon">🍰</span><div><strong>芝士蛋糕</strong><p>绵密芝士，下午茶推荐</p></div><span class="member-product-price">¥16</span></div>' +
        '</div>';
}

function memberRenderOrders() {
    document.getElementById('memberPhonePage').innerHTML =
        '<h2>订单</h2>' +
        '<div class="member-orders">' +
        '<div class="member-order-card"><strong>我的订单</strong><p>本页面仅做静态展示，不调用真实订单列表接口。</p></div>' +
        '<div class="member-empty-state"><span>📦</span><strong>暂无更多订单</strong><span>登录后也不会请求订单列表</span></div>' +
        '</div>';
}

function memberRenderMe() {
    const loggedIn = Boolean(memberProfile);
    const avatar = memberAvatarUrl(loggedIn ? memberProfile.avatarUrl : '');
    const phone = loggedIn ? memberEscapeHtml(memberProfile.phone) : '';
    const memberId = loggedIn ? memberEscapeHtml(memberProfile.memberId) : '';
    const summary = loggedIn
        ? '<div><h3>你好：' + phone + '</h3><p>手机号：' + phone + '</p><p>memberId：' + memberId + '</p></div>'
        : '<div><h3>你好，你还没登录</h3><p>点击此区域登录/注册</p></div>';
    const form = loggedIn
        ? '<button type="button" class="btn" style="margin-top:16px;width:100%;" onclick="memberLogout()">退出登录</button>'
        : '<div class="member-auth-form">' +
          '<input id="memberPhoneInput" value="13888999999" placeholder="手机号" autocomplete="username">' +
          '<input id="memberPasswordInput" value="pwd123456" placeholder="密码" type="password" autocomplete="current-password">' +
          '<input id="memberAvatarInput" placeholder="头像 URL（可选）">' +
          '<button type="button" class="btn" onclick="memberRegister()">注册</button>' +
          '<button type="button" class="btn btn-primary" onclick="memberLogin()">登录</button>' +
          '</div>';
    document.getElementById('memberPhonePage').innerHTML =
        '<h2>我的</h2>' +
        '<div class="member-user-card" onclick="memberFocusLogin()">' +
        '<img class="member-avatar" alt="会员头像" src="' + memberEscapeHtml(avatar) + '">' +
        summary +
        '</div>' +
        form;
}

function memberFocusLogin() {
    const input = document.getElementById('memberPhoneInput');
    if (input) {
        input.focus();
    }
}

function memberAuthInput() {
    return {
        phone: document.getElementById('memberPhoneInput').value.trim(),
        password: document.getElementById('memberPasswordInput').value
    };
}

async function memberRegister() {
    const input = memberAuthInput();
    const avatarUrl = document.getElementById('memberAvatarInput').value.trim();
    try {
        const res = await memberPost('/demo/members/register', {
            phone: input.phone,
            password: input.password,
            avatarUrl: avatarUrl
        });
        const text = await res.text();
        memberAppendLog((res.ok ? '注册成功：' : '注册失败：') + text);
    } catch (e) {
        memberAppendLog('注册请求失败：' + e.message);
    }
}

async function memberLogin() {
    const input = memberAuthInput();
    try {
        const res = await memberPost('/demo/members/login', input);
        const text = await res.text();
        if (!res.ok) {
            memberAppendLog('登录失败：' + text);
            return;
        }
        const data = JSON.parse(text);
        memberToken = data.token;
        memberProfile = data;
        memberSessionDeleted = false;
        localStorage.setItem(MEMBER_TOKEN_STORAGE_KEY, memberToken);
        memberAppendLog('登录成功：' + data.phone);
        memberRender();
    } catch (e) {
        memberAppendLog('登录请求失败：' + e.message);
    }
}

async function memberLoadProfile() {
    if (!memberToken) {
        memberAppendLog('访问个人中心失败：当前未登录');
        return;
    }
    try {
        const res = await memberPost('/demo/members/getProfile', {});
        const text = await res.text();
        memberAppendLog('个人中心（HTTP ' + res.status + '）：' + text);
        if (res.ok) {
            memberProfile = JSON.parse(text);
            memberSessionDeleted = false;
        } else if (res.status === 401) {
            memberProfile = null;
        }
        memberRender();
    } catch (e) {
        memberAppendLog('个人中心请求失败：' + e.message);
    }
}

async function memberLogout() {
    try {
        const res = await memberPost('/demo/members/logout', {});
        memberAppendLog('退出登录（HTTP ' + res.status + '）：' + await res.text());
    } catch (e) {
        memberAppendLog('退出登录请求失败：' + e.message);
    } finally {
        memberToken = '';
        memberProfile = null;
        memberSessionDeleted = false;
        localStorage.removeItem(MEMBER_TOKEN_STORAGE_KEY);
        memberRender();
    }
}

async function memberDeleteSession() {
    if (!memberToken) {
        memberAppendLog('当前无 token 可删除');
        return;
    }
    try {
        const res = await memberPost('/demo/members/deleteSession', { token: memberToken });
        const text = await res.text();
        memberAppendLog('删除 Redis 登录态（HTTP ' + res.status + '）：' + text);
        if (res.ok) {
            memberSessionDeleted = true;
            memberRenderSession();
        }
    } catch (e) {
        memberAppendLog('删除 Redis 登录态请求失败：' + e.message);
    }
}

function memberOrderResultBox() {
    return document.getElementById('memberOrderResult');
}

async function memberOrderCreate() {
    if (!memberToken) {
        memberAppendLog('创建订单失败：请先登录');
        return;
    }
    const amount = document.getElementById('memberOrderAmount').value.trim();
    const delay = document.getElementById('memberOrderDelay').value.trim() || '60s';
    const resultBox = memberOrderResultBox();
    resultBox.className = 'result-box member-order-result loading';
    resultBox.textContent = '创建订单中...';
    try {
        const res = await memberPost('/demo/orders/orderPlace', {
            amount: Number(amount),
            delay: delay
        });
        const text = await res.text();
        const data = JSON.parse(text);
        if (!res.ok) {
            resultBox.className = 'result-box member-order-result error';
            resultBox.textContent = '创建失败：' + text;
            memberAppendLog('创建订单失败：' + text);
            return;
        }
        memberOrderLastOrderId = String(data.orderId);
        memberOrderLastTaskId = String(data.taskId);
        document.getElementById('memberOrderId').value = memberOrderLastOrderId;
        resultBox.className = 'result-box member-order-result';
        resultBox.textContent = JSON.stringify(data, null, 2);
        memberAppendLog('已创建 orderId=' + memberOrderLastOrderId + ' taskId=' + memberOrderLastTaskId
            + ' delay=' + delay);
    } catch (e) {
        resultBox.className = 'result-box member-order-result error';
        resultBox.textContent = '请求失败：' + e.message;
        memberAppendLog('创建订单请求失败：' + e.message);
    }
}

async function memberOrderPay() {
    if (!memberToken) {
        memberAppendLog('模拟支付失败：请先登录');
        return;
    }
    const orderId = document.getElementById('memberOrderId').value.trim() || memberOrderLastOrderId;
    if (!orderId) {
        memberAppendLog('模拟支付失败：请先创建订单或填写 orderId');
        return;
    }
    const resultBox = memberOrderResultBox();
    resultBox.className = 'result-box member-order-result loading';
    resultBox.textContent = '支付中...';
    try {
        const res = await memberPost('/demo/orders/pay', { orderId: orderId });
        const text = await res.text();
        if (!res.ok) {
            resultBox.className = 'result-box member-order-result error';
            resultBox.textContent = '支付失败：' + text;
            memberAppendLog('支付失败（HTTP ' + res.status + '）：' + text);
            return;
        }
        resultBox.className = 'result-box member-order-result';
        resultBox.textContent = text;
        memberAppendLog('已支付 orderId=' + orderId);
    } catch (e) {
        resultBox.className = 'result-box member-order-result error';
        resultBox.textContent = '请求失败：' + e.message;
        memberAppendLog('模拟支付请求失败：' + e.message);
    }
}

async function memberOrderCancel() {
    if (!memberToken) {
        memberAppendLog('取消订单失败：请先登录');
        return;
    }
    const orderId = document.getElementById('memberOrderId').value.trim() || memberOrderLastOrderId;
    if (!orderId) {
        memberAppendLog('取消订单失败：请先创建订单或填写 orderId');
        return;
    }
    const resultBox = memberOrderResultBox();
    resultBox.className = 'result-box member-order-result loading';
    resultBox.textContent = '取消中...';
    try {
        const res = await memberPost('/demo/orders/cancel', { orderId: orderId });
        const text = await res.text();
        if (!res.ok) {
            resultBox.className = 'result-box member-order-result error';
            resultBox.textContent = '取消失败：' + text;
            memberAppendLog('取消失败（HTTP ' + res.status + '）：' + text);
            return;
        }
        resultBox.className = 'result-box member-order-result';
        resultBox.textContent = text;
        memberAppendLog('已取消 orderId=' + orderId);
    } catch (e) {
        resultBox.className = 'result-box member-order-result error';
        resultBox.textContent = '请求失败：' + e.message;
        memberAppendLog('取消订单请求失败：' + e.message);
    }
}

async function memberOrderRefresh() {
    if (!memberToken) {
        memberAppendLog('刷新订单失败：请先登录');
        return;
    }
    const orderId = document.getElementById('memberOrderId').value.trim() || memberOrderLastOrderId;
    if (!orderId) {
        memberAppendLog('刷新订单失败：请填写 orderId');
        return;
    }
    const resultBox = memberOrderResultBox();
    resultBox.className = 'result-box member-order-result loading';
    resultBox.textContent = '查询中...';
    try {
        const [orderRes, taskRes] = await Promise.all([
            memberPost('/demo/orders/get', { orderId: orderId }),
            fetch('/demo/delay-tasks?bizKey=' + encodeURIComponent(orderId))
        ]);
        const orderText = await orderRes.text();
        const taskText = await taskRes.text();
        resultBox.className = orderRes.ok ? 'result-box member-order-result' : 'result-box member-order-result error';
        resultBox.textContent = '订单：\n' + orderText + '\n\n台账：\n' + taskText;
        memberAppendLog('刷新 orderId=' + orderId);
    } catch (e) {
        resultBox.className = 'result-box member-order-result error';
        resultBox.textContent = '请求失败：' + e.message;
        memberAppendLog('刷新订单请求失败：' + e.message);
    }
}

function memberRenderSession() {
    const box = document.getElementById('memberSessionBox');
    if (!box) {
        return;
    }
    if (!memberToken) {
        box.textContent = '未登录';
        return;
    }
    box.textContent =
        '状态=' + (memberSessionDeleted ? 'Redis session 已删除（token 保留用于验证 401）' : '已持有 token') +
        '\nTTL=24h（app.auth.session-ttl 可配置）' +
        '\ntoken=' + memberToken +
        '\nRedis key=demo2:auth:session:' + memberToken +
        '\nphone=' + (memberProfile && memberProfile.phone ? memberProfile.phone : '') +
        '\nmemberId=' + (memberProfile && memberProfile.memberId ? memberProfile.memberId : '');
}

memberRender();
if (memberToken) {
    memberLoadProfile();
}
