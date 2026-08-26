// ========== 会员 C 端 Demo ==========
const MEMBER_TOKEN_STORAGE_KEY = 'demo2MemberToken';
let memberToken = localStorage.getItem(MEMBER_TOKEN_STORAGE_KEY) || '';
let memberProfile = null;
let memberMobileTab = 'home';
let memberMobileView = 'home';
let memberSelectedProductId = '';
let memberSessionDeleted = false;
let memberOrderLastOrderId = '';
let memberOrderLastTaskId = '';
let memberToastTimer = null;
let memberHomeRenderSeq = 0;
let memberDetailRenderSeq = 0;

/** 雪花 ID：后端 Long 经 Jackson 序列化为字符串，前端始终按 string 传递 */
function memberSnowflakeId(value) {
    return value == null || value === '' ? '' : String(value);
}

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

function memberEnsureToast() {
    const screen = document.querySelector('.member-phone-screen');
    if (!screen) {
        return null;
    }
    let toast = document.getElementById('memberToast');
    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'memberToast';
        toast.className = 'member-toast';
        toast.innerHTML = '<span class="member-toast-icon" aria-hidden="true">!</span>'
            + '<span class="member-toast-text"></span>';
        toast.addEventListener('click', memberHideToast);
        screen.appendChild(toast);
    }
    return toast;
}

function memberHideToast() {
    const toast = document.getElementById('memberToast');
    if (toast) {
        toast.classList.remove('show');
    }
    if (memberToastTimer) {
        clearTimeout(memberToastTimer);
        memberToastTimer = null;
    }
}

function memberShowError(message) {
    const toast = memberEnsureToast();
    const text = message || '请求失败';
    if (!toast) {
        return;
    }
    toast.querySelector('.member-toast-text').textContent = text;
    toast.classList.add('show');
    if (memberToastTimer) {
        clearTimeout(memberToastTimer);
    }
    memberToastTimer = setTimeout(memberHideToast, 2800);
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

async function memberRequest(url, body, options) {
    const silent = options && options.silent;
    const res = await memberPost(url, body);
    let result;
    try {
        result = JSON.parse(await res.text());
    } catch (e) {
        const message = '响应解析失败';
        if (!silent) {
            memberShowError(message);
        }
        throw new Error(message);
    }
    if (result.code !== 0) {
        const message = result.message || '请求失败';
        if (!silent) {
            memberShowError(message);
        }
        const error = new Error(message);
        error.code = result.code;
        throw error;
    }
    return result.data;
}

function memberIsAuthError(message) {
    return message === '未登录或登录已失效' || message === 'token 无效';
}

function memberSwitchMobileTab(tab) {
    if (!['home', 'orders', 'me'].includes(tab)) {
        return;
    }
    memberMobileTab = tab;
    if (tab === 'home') {
        memberMobileView = 'home';
    }
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
        if (memberMobileView === 'detail') {
            memberRenderDetail();
        } else {
            memberRenderHome();
        }
    } else if (memberMobileTab === 'orders') {
        memberRenderOrders();
    } else {
        memberRenderMe();
    }
    memberRenderSession();
}

function memberHomeBannerHtml() {
    return '<div class="member-home-banner"><strong>今日精选</strong>'
        + '<span>商品来自接口 · 右侧面板可下单/支付/取消</span></div>';
}

function memberProductEmoji(name) {
    const icons = { '拿铁': '☕', '生椰拿铁': '🥥', '芝士蛋糕': '🍰' };
    return icons[name] || '🛍️';
}

function memberProductIconHtml(name, coverUrl) {
    if (coverUrl) {
        return '<img class="member-product-icon member-product-icon-img" alt="" src="'
            + memberEscapeHtml(coverUrl) + '">';
    }
    return '<span class="member-product-icon">' + memberProductEmoji(name) + '</span>';
}

function memberDetailCoverHtml(name, coverUrl) {
    if (coverUrl) {
        return '<img class="member-detail-cover" alt="' + memberEscapeHtml(name) + '" src="'
            + memberEscapeHtml(coverUrl) + '">';
    }
    return '<div class="member-detail-cover-fallback">' + memberProductEmoji(name) + '</div>';
}

function memberProductCardHtml(item) {
    const productId = memberSnowflakeId(item.productId);
    const sold = Number(item.sellStock) > 0
        ? '<span class="member-product-sold">已售 ' + memberEscapeHtml(item.sellStock) + '</span>'
        : '';
    return '<div class="member-product-card" data-product-id="' + memberEscapeHtml(productId) + '" onclick="memberOpenProduct(this.dataset.productId)">'
        + memberProductIconHtml(item.productName, item.coverUrl)
        + '<div class="member-product-info"><strong>' + memberEscapeHtml(item.productName) + '</strong>'
        + '<p>' + memberEscapeHtml(item.subtitle || '') + '</p>' + sold + '</div>'
        + '<span class="member-product-price">¥' + memberEscapeHtml(item.sellPrice) + '</span></div>';
}

async function memberRenderHome() {
    const page = document.getElementById('memberPhonePage');
    const seq = ++memberHomeRenderSeq;
    page.innerHTML = '<h2>首页</h2>' + memberHomeBannerHtml()
        + '<div class="member-products loading">加载商品中...</div>';
    try {
        const data = await memberRequest('/demo/products/listProducts', {}, { silent: true });
        if (seq !== memberHomeRenderSeq || memberMobileTab !== 'home' || memberMobileView !== 'home') {
            return;
        }
        const items = (data && data.items) ? data.items : [];
        page.innerHTML = '<h2>首页</h2>' + memberHomeBannerHtml()
            + '<div class="member-products">'
            + (items.length ? items.map(memberProductCardHtml).join('') : '<div class="member-empty-state">暂无商品</div>')
            + '</div>';
    } catch (e) {
        if (seq !== memberHomeRenderSeq || memberMobileTab !== 'home' || memberMobileView !== 'home') {
            return;
        }
        page.innerHTML = '<h2>首页</h2>' + memberHomeBannerHtml()
            + '<div class="member-products error">商品加载失败</div>';
    }
}

function memberOpenProduct(productId) {
    memberHomeRenderSeq++;
    memberSelectedProductId = memberSnowflakeId(productId);
    memberMobileView = 'detail';
    memberRender();
}

function memberBackHome() {
    memberMobileView = 'home';
    memberSelectedProductId = '';
    memberRender();
}

async function memberRenderDetail() {
    const page = document.getElementById('memberPhonePage');
    const seq = ++memberDetailRenderSeq;
    page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackHome()">← 返回</button>'
        + '<div class="member-detail loading">加载中...</div>';
    if (!memberSelectedProductId) {
        memberBackHome();
        return;
    }
    try {
        const item = await memberRequest('/demo/products/getProduct',
            { productId: memberSelectedProductId }, { silent: true });
        if (seq !== memberDetailRenderSeq || memberMobileView !== 'detail') {
            return;
        }
        const sold = Number(item.sellStock) > 0
            ? '<p class="member-detail-sold">已售 ' + memberEscapeHtml(item.sellStock) + '</p>' : '';
        const stock = '<p class="member-detail-stock">库存 ' + memberEscapeHtml(item.availableStock) + '</p>';
        page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackHome()">← 返回</button>'
            + '<div class="member-detail">'
            + '<div class="member-detail-hero">' + memberDetailCoverHtml(item.productName, item.coverUrl) + '</div>'
            + '<h2>' + memberEscapeHtml(item.productName) + '</h2>'
            + '<p class="member-detail-subtitle">' + memberEscapeHtml(item.subtitle || '') + '</p>'
            + '<p class="member-detail-price">¥' + memberEscapeHtml(item.sellPrice) + '</p>'
            + sold + stock
            + '<div class="member-detail-content">' + memberEscapeHtml(item.detailContent || '') + '</div>'
            + '<button type="button" class="btn member-detail-buy" disabled title="订单模块后续接入">立即购买</button>'
            + '</div>';
    } catch (e) {
        if (seq !== memberDetailRenderSeq || memberMobileView !== 'detail') {
            return;
        }
        page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackHome()">← 返回</button>'
            + '<div class="member-detail error">商品详情加载失败</div>';
    }
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
        : '';
    const cardClass = loggedIn ? 'member-user-card member-user-card--logged-in' : 'member-user-card';
    const cardClick = loggedIn ? '' : ' onclick="memberOpenAuth()"';
    document.getElementById('memberPhonePage').innerHTML =
        '<h2>我的</h2>' +
        '<div class="' + cardClass + '"' + cardClick + '>' +
        '<img class="member-avatar" alt="会员头像" src="' + memberEscapeHtml(avatar) + '">' +
        summary +
        '</div>' +
        form;
}

function memberOpenAuth() {
    MemberAuth.open({ mode: 'login' });
}

function memberRequireLogin() {
    if (memberToken) {
        return true;
    }
    MemberAuth.open({ mode: 'login' });
    return false;
}

async function memberLoadProfile() {
    if (!memberToken) {
        memberAppendLog('访问个人中心失败：当前未登录');
        return;
    }
    try {
        memberProfile = await memberRequest('/demo/members/getProfile', {}, { silent: true });
        memberSessionDeleted = false;
        memberAppendLog('个人中心：' + JSON.stringify(memberProfile));
        memberRender();
    } catch (e) {
        memberAppendLog('个人中心失败：' + e.message);
        if (memberIsAuthError(e.message)) {
            memberProfile = null;
        }
        memberRender();
    }
}

async function memberLogout() {
    try {
        const data = await memberRequest('/demo/members/logout', {});
        memberAppendLog('退出登录：' + JSON.stringify(data));
    } catch (e) {
        memberAppendLog('退出登录失败：' + e.message);
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
        const data = await memberRequest('/demo/members/deleteSession', { token: memberToken });
        memberAppendLog('删除 Redis 登录态：' + JSON.stringify(data));
        memberSessionDeleted = true;
        memberRenderSession();
    } catch (e) {
        memberAppendLog('删除 Redis 登录态失败：' + e.message);
    }
}

function memberOrderResultBox() {
    return document.getElementById('memberOrderResult');
}

async function memberOrderCreate() {
    if (!memberRequireLogin()) {
        return;
    }
    const amount = document.getElementById('memberOrderAmount').value.trim();
    const delay = document.getElementById('memberOrderDelay').value.trim() || '60s';
    const resultBox = memberOrderResultBox();
    resultBox.className = 'result-box member-order-result loading';
    resultBox.textContent = '创建订单中...';
    try {
        const data = await memberRequest('/demo/orders/orderPlace', {
            amount: Number(amount),
            delay: delay
        });
        memberOrderLastOrderId = memberSnowflakeId(data.orderId);
        memberOrderLastTaskId = memberSnowflakeId(data.taskId);
        document.getElementById('memberOrderId').value = memberOrderLastOrderId;
        resultBox.className = 'result-box member-order-result';
        resultBox.textContent = JSON.stringify(data, null, 2);
        memberAppendLog('已创建 orderId=' + memberOrderLastOrderId + ' taskId=' + memberOrderLastTaskId
            + ' delay=' + delay);
    } catch (e) {
        resultBox.className = 'result-box member-order-result error';
        resultBox.textContent = '创建失败：' + e.message;
        memberAppendLog('创建订单失败：' + e.message);
    }
}

async function memberOrderPay() {
    if (!memberRequireLogin()) {
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
        const data = await memberRequest('/demo/orders/pay', { orderId: orderId });
        resultBox.className = 'result-box member-order-result';
        resultBox.textContent = JSON.stringify(data, null, 2);
        memberAppendLog('已支付 orderId=' + orderId);
    } catch (e) {
        resultBox.className = 'result-box member-order-result error';
        resultBox.textContent = '支付失败：' + e.message;
        memberAppendLog('支付失败：' + e.message);
    }
}

async function memberOrderCancel() {
    if (!memberRequireLogin()) {
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
        const data = await memberRequest('/demo/orders/cancel', { orderId: orderId });
        resultBox.className = 'result-box member-order-result';
        resultBox.textContent = JSON.stringify(data, null, 2);
        memberAppendLog('已取消 orderId=' + orderId);
    } catch (e) {
        resultBox.className = 'result-box member-order-result error';
        resultBox.textContent = '请求失败：' + e.message;
        memberAppendLog('取消订单请求失败：' + e.message);
    }
}

async function memberOrderRefresh() {
    if (!memberRequireLogin()) {
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
        const orderData = await memberRequest('/demo/orders/get', { orderId: orderId });
        const taskRes = await fetch('/demo/delay-tasks?bizKey=' + encodeURIComponent(orderId));
        const taskText = await taskRes.text();
        resultBox.className = 'result-box member-order-result';
        resultBox.textContent = '订单：\n' + JSON.stringify(orderData, null, 2) + '\n\n台账：\n' + taskText;
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
        '状态=' + (memberSessionDeleted ? 'Redis session 已删除（token 保留用于验证未登录）' : '已持有 token') +
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
