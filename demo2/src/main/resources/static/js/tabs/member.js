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
let memberPreviewRenderSeq = 0;
let memberOrdersRenderSeq = 0;
let memberOrderDetailRenderSeq = 0;
let memberPreviewQty = 1;
let memberOrderListTab = 'ALL';
let memberOrderListPageNo = 1;
let memberOrderListHasMore = true;
let memberOrderListLoadingMore = false;
const MEMBER_ORDER_PAGE_SIZE = 10;
let memberPreviewData = null;
let memberOrderCountdownTimer = null;
let memberOrderCountdownRefreshOnce = '';
let memberActionBusy = false;

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

function memberSetButtonBusy(btn, busy, loadingText) {
    if (!btn) {
        return;
    }
    if (busy) {
        if (!btn.dataset.idleLabel) {
            btn.dataset.idleLabel = (btn.textContent || '').trim();
        }
        btn.disabled = true;
        btn.classList.add('member-btn-busy');
        btn.setAttribute('aria-busy', 'true');
        btn.innerHTML = '<span class="member-btn-spinner" aria-hidden="true"></span>'
            + memberEscapeHtml(loadingText || '处理中');
        return;
    }
    btn.disabled = false;
    btn.classList.remove('member-btn-busy');
    btn.removeAttribute('aria-busy');
    btn.textContent = btn.dataset.idleLabel || btn.textContent;
    delete btn.dataset.idleLabel;
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
    if (tab === 'home' || tab === 'orders') {
        memberMobileView = 'home';
    }
    memberRender();
}

function memberRender() {
    const page = document.getElementById('memberPhonePage');
    if (!page) {
        return;
    }
    if (memberMobileView !== 'orderDetail') {
        memberStopOrderCountdown();
    }
    document.getElementById('memberNavHome').classList.toggle('active', memberMobileTab === 'home');
    document.getElementById('memberNavOrders').classList.toggle('active', memberMobileTab === 'orders');
    document.getElementById('memberNavMe').classList.toggle('active', memberMobileTab === 'me');
    if (memberMobileTab === 'home') {
        if (memberMobileView === 'preview') {
            memberRenderPreview();
        } else if (memberMobileView === 'orderDetail') {
            memberRenderOrderDetail();
        } else if (memberMobileView === 'detail') {
            memberRenderDetail();
        } else {
            memberRenderHome();
        }
    } else if (memberMobileTab === 'orders') {
        if (memberMobileView === 'orderDetail') {
            memberRenderOrderDetail();
        } else {
            memberRenderOrders();
        }
    } else {
        memberRenderMe();
    }
    memberRenderSession();
}

function memberHomeBannerHtml() {
    return '<div class="member-home-banner"><strong>今日精选</strong></div>';
}

function memberProductEmoji(name) {
    const icons = { '拿铁': '☕', '生椰拿铁': '🥥', '芝士蛋糕': '🍰' };
    return icons[name] || '🛍️';
}

function memberResolvedCoverUrl(name, coverUrl) {
    if (coverUrl) {
        return coverUrl;
    }
    const covers = {
        '拿铁': '/images/product/latte.png',
        '生椰拿铁': '/images/product/coconut-latte.png',
        '芝士蛋糕': '/images/product/cheesecake.png'
    };
    return covers[name] || '';
}

function memberProductIconHtml(name, coverUrl) {
    const src = memberResolvedCoverUrl(name, coverUrl);
    if (src) {
        return '<img class="member-product-icon member-product-icon-img" alt="'
            + memberEscapeHtml(name || '') + '" src="' + memberEscapeHtml(src) + '">';
    }
    return '<span class="member-product-icon">' + memberProductEmoji(name) + '</span>';
}

function memberDetailCoverHtml(name, coverUrl) {
    const src = memberResolvedCoverUrl(name, coverUrl);
    if (src) {
        return '<img class="member-detail-cover" alt="' + memberEscapeHtml(name) + '" src="'
            + memberEscapeHtml(src) + '">';
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

function memberBuyNow(productId) {
    memberSelectedProductId = memberSnowflakeId(productId);
    if (!memberToken) {
        MemberAuth.open({
            mode: 'login',
            onSuccess: function () {
                memberMobileView = 'preview';
                memberPreviewQty = 1;
                memberRender();
            }
        });
        return;
    }
    memberMobileView = 'preview';
    memberPreviewQty = 1;
    memberRender();
}

function memberBackFromPreview() {
    memberMobileView = 'detail';
    memberRender();
}

function memberBackFromOrderDetail() {
    if (memberMobileTab === 'orders') {
        memberMobileView = 'home';
        memberRender();
        return;
    }
    memberMobileView = memberSelectedProductId ? 'detail' : 'home';
    memberRender();
}

function memberPreviewQtyMax(preview) {
    const first = preview && preview.items && preview.items[0] ? preview.items[0] : null;
    const stock = first != null ? Number(first.availableStock) : NaN;
    const bounded = Number.isFinite(stock) ? stock : 99999;
    return Math.min(99999, bounded);
}

function memberOnPreviewQtyInput(el) {
    const max = memberPreviewQtyMax(memberPreviewData);
    let qty = parseInt(el.value, 10);
    if (!Number.isFinite(qty) || qty < 1) {
        qty = 1;
    }
    if (max >= 1 && qty > max) {
        qty = max;
    }
    el.value = String(qty);
    if (qty === memberPreviewQty) {
        return;
    }
    memberPreviewQty = qty;
    memberRenderPreview();
}

function memberPreviewViewHtml(preview) {
    const items = preview.items || [];
    const maxQty = memberPreviewQtyMax(preview);
    const first = items[0] || {};
    const lines = items.length
        ? items.map(memberOrderLineHtml).join('')
        : memberOrderLineHtml({ productName: '商品', qty: memberPreviewQty, sellPrice: preview.amount });
    const stock = first.availableStock != null
        ? memberOrderMetaRow('可售库存', first.availableStock)
        : '';
    return '<button type="button" class="member-back-btn" onclick="memberBackFromPreview()">← 返回</button>'
        + '<h2>确认订单</h2>'
        + '<div class="member-order-card member-order-detail-card">'
        + '<div class="member-order-card-head">'
        + '<span class="member-order-card-no">确认下单</span>'
        + '<span class="member-order-card-status member-order-card-status--submit">待提交</span></div>'
        + '<div class="member-order-card-lines">' + lines + '</div>'
        + '<div class="member-order-detail-meta">'
        + '<label class="member-order-detail-meta-row member-preview-qty-row">数量'
        + '<input type="number" class="member-preview-qty" min="1" max="'
        + memberEscapeHtml(maxQty) + '" value="' + memberEscapeHtml(memberPreviewQty)
        + '" onchange="memberOnPreviewQtyInput(this)" onblur="memberOnPreviewQtyInput(this)"></label>'
        + stock
        + '</div>'
        + '<div class="member-order-card-foot">'
        + '<span></span>'
        + '<span class="member-order-card-amount">合计 <em>¥' + memberEscapeHtml(preview.amount) + '</em></span></div>'
        + '<div class="member-order-detail-actions">'
        + '<button type="button" class="btn btn-primary" id="memberPlaceSubmit" onclick="memberPlaceOrder(this)">提交订单</button>'
        + '</div></div>';
}

async function memberRenderPreview() {
    const page = document.getElementById('memberPhonePage');
    const seq = ++memberPreviewRenderSeq;
    page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackFromPreview()">← 返回</button>'
        + '<div class="member-detail loading">预览中...</div>';
    if (!memberSelectedProductId) {
        memberBackHome();
        return;
    }
    try {
        const preview = await memberRequest('/demo/orders/preview', {
            items: [{ productId: memberSelectedProductId, qty: memberPreviewQty }]
        });
        if (seq !== memberPreviewRenderSeq || memberMobileView !== 'preview') {
            return;
        }
        memberPreviewData = preview;
        page.innerHTML = memberPreviewViewHtml(preview);
    } catch (e) {
        if (seq !== memberPreviewRenderSeq || memberMobileView !== 'preview') {
            return;
        }
        page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackFromPreview()">← 返回</button>'
            + '<div class="member-detail error">预览失败</div>';
    }
}

async function memberPlaceOrder(btn) {
    btn = btn || document.getElementById('memberPlaceSubmit');
    if (memberActionBusy) {
        return;
    }
    if (!memberPreviewData) {
        return;
    }
    memberActionBusy = true;
    memberSetButtonBusy(btn, true, '提交中');
    try {
        const data = await memberRequest('/demo/orders/orderPlace', {
            placeToken: memberPreviewData.placeToken,
            items: (memberPreviewData.items || []).map(function (row) {
                return {
                    productId: memberSnowflakeId(row.productId),
                    qty: row.qty,
                    sellPrice: row.sellPrice
                };
            })
        });
        memberOrderLastOrderId = memberSnowflakeId(data.orderId);
        memberAppendLog('已下单 orderId=' + memberOrderLastOrderId);
        memberMobileView = 'orderDetail';
        memberRender();
    } catch (e) {
        if (e.code === 30008 || e.code === 30009) {
            memberShowError('价格或凭证已失效，请刷新预览');
            memberRenderPreview();
            return;
        }
        memberAppendLog('下单失败：' + e.message);
        memberSetButtonBusy(btn, false);
    } finally {
        memberActionBusy = false;
    }
}

function memberOrderStatusLabel(status) {
    if (status === 'SUBMIT') {
        return '待支付';
    }
    if (status === 'COMPLETED') {
        return '已完成';
    }
    if (status === 'CANCEL') {
        return '已取消';
    }
    return status || '';
}

function memberFillOrderId(orderId) {
    memberOrderLastOrderId = memberSnowflakeId(orderId);
}

function memberOpenOrder(orderId) {
    const nextId = memberSnowflakeId(orderId);
    if (memberOrderCountdownRefreshOnce !== nextId) {
        memberOrderCountdownRefreshOnce = '';
    }
    memberFillOrderId(nextId);
    memberMobileView = 'orderDetail';
    memberRender();
}

function memberOrderMetaRow(label, value) {
    if (!value) {
        return '';
    }
    return '<div class="member-order-detail-meta-row"><span>' + memberEscapeHtml(label)
        + '</span><span>' + memberEscapeHtml(memberDateTimeLabel(value)) + '</span></div>';
}

function memberDateTimeLabel(value) {
    return String(value || '').replace('T', ' ').slice(0, 19);
}

function memberParseDateTime(value) {
    if (!value) {
        return null;
    }
    const date = new Date(String(value).trim().replace(' ', 'T'));
    return Number.isFinite(date.getTime()) ? date : null;
}

function memberFormatRemain(ms) {
    if (ms <= 0) {
        return '00:00';
    }
    const totalSec = Math.ceil(ms / 1000);
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}

function memberStopOrderCountdown() {
    if (memberOrderCountdownTimer) {
        clearInterval(memberOrderCountdownTimer);
        memberOrderCountdownTimer = null;
    }
}

function memberStartOrderCountdown(deadlineStr, orderId, seq) {
    memberStopOrderCountdown();
    const box = document.getElementById('memberOrderCountdownBox');
    const timeEl = document.getElementById('memberOrderCountdown');
    if (!box || !timeEl) {
        return;
    }
    const deadline = memberParseDateTime(deadlineStr);
    if (!deadline) {
        timeEl.textContent = '--:--';
        return;
    }
    const tick = function () {
        if (seq !== memberOrderDetailRenderSeq || memberMobileView !== 'orderDetail') {
            memberStopOrderCountdown();
            return;
        }
        const remain = deadline.getTime() - Date.now();
        timeEl.textContent = memberFormatRemain(remain);
        box.classList.toggle('member-order-countdown--urgent', remain > 0 && remain < 60000);
        box.classList.toggle('member-order-countdown--expired', remain <= 0);
        if (remain > 0) {
            return;
        }
        memberStopOrderCountdown();
        if (memberOrderCountdownRefreshOnce !== orderId) {
            memberOrderCountdownRefreshOnce = orderId;
            memberRenderOrderDetail();
        }
    };
    tick();
    if (memberOrderCountdownTimer == null && deadline.getTime() > Date.now()) {
        memberOrderCountdownTimer = setInterval(tick, 1000);
    }
}

function memberOrderDetailHtml(order) {
    const orderId = memberSnowflakeId(order.orderId);
    const status = order.orderStatus;
    const items = order.items || [];
    const lines = items.length
        ? items.map(memberOrderLineHtml).join('')
        : memberOrderLineHtml({ productName: '订单', qty: '', sellPrice: order.amount });
    const countdown = status === 'SUBMIT'
        ? '<div class="member-order-countdown" id="memberOrderCountdownBox">'
            + '<span class="member-order-countdown-label">剩余支付时间</span>'
            + '<span class="member-order-countdown-time" id="memberOrderCountdown">--:--</span>'
            + '<span class="member-order-countdown-hint">超时未支付将自动取消，请尽快付款</span></div>'
        : '';
    const meta = '<div class="member-order-detail-meta">'
        + memberOrderMetaRow('创建时间', order.createdAt)
        + memberOrderMetaRow('付款时间', order.payTime)
        + memberOrderMetaRow('取消时间', order.cancelTime)
        + '</div>';
    const actions = status === 'SUBMIT'
        ? '<div class="member-order-detail-actions">'
            + '<button type="button" class="btn" id="memberOrderCancelBtn" onclick="memberCancelCurrentOrder(this)">取消</button>'
            + '<button type="button" class="btn btn-primary" id="memberOrderPayBtn" onclick="memberPayCurrentOrder(this)">去支付</button>'
            + '</div>'
        : '';
    return '<button type="button" class="member-back-btn" onclick="memberBackFromOrderDetail()">← 返回</button>'
        + '<h2>订单详情</h2>'
        + '<div class="member-order-card member-order-detail-card">'
        + '<div class="member-order-card-head">'
        + '<span class="member-order-card-no" title="' + memberEscapeHtml(orderId) + '">订单号 '
        + memberEscapeHtml(orderId) + '</span>'
        + '<span class="member-order-card-status ' + memberOrderStatusClass(status) + '">'
        + memberEscapeHtml(memberOrderStatusLabel(status)) + '</span></div>'
        + countdown
        + '<div class="member-order-card-lines">' + lines + '</div>'
        + '<div class="member-order-card-foot">'
        + '<span></span>'
        + '<span class="member-order-card-amount">' + memberEscapeHtml(memberOrderAmountLabel(status))
        + ' <em>¥' + memberEscapeHtml(order.amount) + '</em></span></div>'
        + meta
        + actions
        + '</div>';
}

async function memberRenderOrderDetail() {
    const page = document.getElementById('memberPhonePage');
    const seq = ++memberOrderDetailRenderSeq;
    memberStopOrderCountdown();
    const orderId = memberOrderLastOrderId;
    page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackFromOrderDetail()">← 返回</button>'
        + '<div class="member-detail loading">加载中...</div>';
    if (!orderId) {
        memberBackFromOrderDetail();
        return;
    }
    try {
        const order = await memberRequest('/demo/orders/get', { orderId: orderId });
        if (seq !== memberOrderDetailRenderSeq || memberMobileView !== 'orderDetail') {
            return;
        }
        page.innerHTML = memberOrderDetailHtml(order);
        if (order.orderStatus === 'SUBMIT') {
            memberStartOrderCountdown(order.payDeadline, memberSnowflakeId(order.orderId), seq);
        }
    } catch (e) {
        if (seq !== memberOrderDetailRenderSeq || memberMobileView !== 'orderDetail') {
            return;
        }
        page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackFromOrderDetail()">← 返回</button>'
            + '<div class="member-detail error">订单详情加载失败</div>';
    }
}

async function memberMutateCurrentOrder(url, actionLabel, clickedBtn) {
    if (memberActionBusy) {
        return;
    }
    memberActionBusy = true;
    const buttons = document.querySelectorAll('#memberOrderPayBtn, #memberOrderCancelBtn, .member-order-card-actions button');
    buttons.forEach(function (btn) {
        btn.disabled = true;
    });
    memberSetButtonBusy(clickedBtn, true, actionLabel === '支付' ? '支付中' : '取消中');
    try {
        await memberRequest(url, { orderId: memberOrderLastOrderId });
        memberAppendLog(actionLabel + '成功 orderId=' + memberOrderLastOrderId);
        if (memberMobileTab === 'orders') {
            memberMobileView = 'home';
            memberRender();
        } else {
            memberRenderOrderDetail();
        }
    } catch (e) {
        memberAppendLog(actionLabel + '失败：' + e.message);
        memberSetButtonBusy(clickedBtn, false);
        buttons.forEach(function (btn) {
            btn.disabled = false;
        });
    } finally {
        memberActionBusy = false;
    }
}

async function memberPayCurrentOrder(btn) {
    await memberMutateCurrentOrder('/demo/orders/pay', '支付', btn || document.getElementById('memberOrderPayBtn'));
}

async function memberCancelCurrentOrder(btn) {
    await memberMutateCurrentOrder('/demo/orders/cancel', '取消', btn || document.getElementById('memberOrderCancelBtn'));
}

function memberOrderTabBtn(tab, label, count) {
    const active = memberOrderListTab === tab ? ' active' : '';
    const badge = (tab !== 'ALL' && Number(count) > 0)
        ? '<span class="member-order-badge">' + memberEscapeHtml(count) + '</span>'
        : '';
    return '<button type="button" class="member-order-tab' + active + '" onclick="memberSwitchOrderListTab(\'' + tab + '\')">'
        + memberEscapeHtml(label) + badge + '</button>';
}

function memberOrderTabsHtml(counts) {
    const pending = counts && counts.pendingCount != null ? counts.pendingCount : 0;
    const completed = counts && counts.completedCount != null ? counts.completedCount : 0;
    return '<div class="member-order-tabs">'
        + memberOrderTabBtn('ALL', '全部', 0)
        + memberOrderTabBtn('SUBMIT', '待支付', pending)
        + memberOrderTabBtn('COMPLETED', '已完成', completed)
        + '</div>';
}

function memberOrderDateLabel(createdAt) {
    const match = String(createdAt || '').match(/(\d{4})-(\d{2})-(\d{2})/);
    return match ? match[2] + '.' + match[3] : '';
}

function memberOrderStatusClass(status) {
    if (status === 'SUBMIT') {
        return 'member-order-card-status--submit';
    }
    if (status === 'COMPLETED') {
        return 'member-order-card-status--done';
    }
    if (status === 'CANCEL') {
        return 'member-order-card-status--cancel';
    }
    return '';
}

function memberOrderAmountLabel(status) {
    if (status === 'SUBMIT') {
        return '需付款';
    }
    if (status === 'COMPLETED') {
        return '实付款';
    }
    return '合计';
}

function memberOrderLineHtml(row) {
    return '<div class="member-order-line">'
        + memberProductIconHtml(row.productName, row.coverUrl)
        + '<div class="member-order-line-info"><strong>' + memberEscapeHtml(row.productName || '') + '</strong></div>'
        + '<div class="member-order-line-meta"><span>¥' + memberEscapeHtml(row.sellPrice) + '</span>'
        + '<span class="member-order-line-qty">×' + memberEscapeHtml(row.qty) + '</span></div>'
        + '</div>';
}

function memberOrderCardHtml(item) {
    const orderId = memberSnowflakeId(item.orderId);
    const status = item.orderStatus;
    const lines = (item.items && item.items.length)
        ? item.items.map(memberOrderLineHtml).join('')
        : memberOrderLineHtml({ productName: '订单', qty: '', sellPrice: item.amount });
    const hint = status === 'SUBMIT'
        ? '<div class="member-order-card-hint">订单即将关闭，建议尽快付款</div>'
        : '';
    const date = memberOrderDateLabel(item.createdAt);
    const actions = status === 'SUBMIT'
        ? '<div class="member-order-card-actions">'
            + '<button type="button" class="btn member-order-card-btn" onclick="event.stopPropagation();memberCancelOrderFromList(this,\''
            + memberEscapeHtml(orderId) + '\')">取消</button>'
            + '<button type="button" class="btn btn-primary member-order-card-btn" onclick="event.stopPropagation();memberPayOrderFromList(this,\''
            + memberEscapeHtml(orderId) + '\')">去支付</button>'
            + '</div>'
        : '';
    return '<div class="member-order-card member-order-list-card" onclick="memberOpenOrder(\''
        + memberEscapeHtml(orderId) + '\')">'
        + '<div class="member-order-card-head">'
        + '<span class="member-order-card-no" title="' + memberEscapeHtml(orderId) + '">订单号 '
        + memberEscapeHtml(orderId) + '</span>'
        + '<span class="member-order-card-status ' + memberOrderStatusClass(status) + '">'
        + memberEscapeHtml(memberOrderStatusLabel(status)) + '</span></div>'
        + '<div class="member-order-card-lines">' + lines + '</div>'
        + hint
        + '<div class="member-order-card-foot">'
        + '<span>' + memberEscapeHtml(date) + '</span>'
        + '<span class="member-order-card-amount">' + memberEscapeHtml(memberOrderAmountLabel(status))
        + ' <em>¥' + memberEscapeHtml(item.amount) + '</em></span></div>'
        + actions
        + '</div>';
}

function memberPayOrderFromList(btn, orderId) {
    memberFillOrderId(orderId);
    return memberMutateCurrentOrder('/demo/orders/pay', '支付', btn);
}

function memberCancelOrderFromList(btn, orderId) {
    memberFillOrderId(orderId);
    return memberMutateCurrentOrder('/demo/orders/cancel', '取消', btn);
}

function memberSwitchOrderListTab(tab) {
    memberOrderListTab = tab;
    memberRenderOrders();
}

function memberOrderListHint(text) {
    const el = document.getElementById('memberOrderListHint');
    if (el) {
        el.textContent = text || '';
    }
}

function memberBindOrderListScroll() {
    const page = document.getElementById('memberPhonePage');
    if (!page || page.dataset.orderScrollBound === '1') {
        return;
    }
    page.dataset.orderScrollBound = '1';
    page.addEventListener('scroll', memberOnOrderListScroll);
}

function memberOnOrderListScroll() {
    if (memberMobileTab !== 'orders' || memberMobileView === 'orderDetail') {
        return;
    }
    const page = document.getElementById('memberPhonePage');
    if (!page || memberOrderListLoadingMore || !memberOrderListHasMore) {
        return;
    }
    if (page.scrollHeight - page.scrollTop - page.clientHeight < 80) {
        memberLoadOrderListMore();
    }
}

function memberMaybeLoadMoreOrders() {
    const page = document.getElementById('memberPhonePage');
    if (!page || memberMobileTab !== 'orders' || memberMobileView === 'orderDetail') {
        return;
    }
    if (page.scrollHeight <= page.clientHeight + 80) {
        memberLoadOrderListMore();
    }
}

function memberOrderListHasNext(loadedCount, list) {
    const total = Number(list && list.total) || 0;
    const items = (list && list.items) ? list.items : [];
    return items.length > 0 && loadedCount < total;
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
            + '<button type="button" class="btn btn-primary member-detail-buy" onclick="memberBuyNow(\''
            + memberSnowflakeId(item.productId) + '\')">立即购买</button>'
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
    const page = document.getElementById('memberPhonePage');
    if (!memberRequireLogin()) {
        page.innerHTML = '<h2>订单</h2><div class="member-empty-state">请先登录</div>';
        return;
    }
    memberLoadOrderList();
}

async function memberLoadOrderList() {
    const page = document.getElementById('memberPhonePage');
    const seq = ++memberOrdersRenderSeq;
    memberOrderListPageNo = 1;
    memberOrderListHasMore = true;
    memberOrderListLoadingMore = true;
    page.scrollTop = 0;
    page.innerHTML = '<h2>订单</h2>' + memberOrderTabsHtml({})
        + '<div class="member-orders loading">加载中...</div>';
    try {
        const [counts, list] = await Promise.all([
            memberRequest('/demo/orders/counts', {}),
            memberRequest('/demo/orders/list', {
                tab: memberOrderListTab,
                pageNo: 1,
                pageSize: MEMBER_ORDER_PAGE_SIZE
            })
        ]);
        if (seq !== memberOrdersRenderSeq || memberMobileTab !== 'orders' || memberMobileView === 'orderDetail') {
            return;
        }
        const items = (list && list.items) ? list.items : [];
        memberOrderListHasMore = memberOrderListHasNext(items.length, list);
        memberOrderListLoadingMore = false;
        page.innerHTML = '<h2>订单</h2>' + memberOrderTabsHtml(counts || {})
            + '<div class="member-orders">'
            + (items.length ? items.map(memberOrderCardHtml).join('') : '<div class="member-empty-state">暂无订单</div>')
            + '</div>'
            + '<div class="member-order-list-hint" id="memberOrderListHint">'
            + (items.length && memberOrderListHasMore ? '继续上滑加载更多' : '')
            + '</div>';
        memberBindOrderListScroll();
        memberMaybeLoadMoreOrders();
    } catch (e) {
        memberOrderListLoadingMore = false;
        if (seq !== memberOrdersRenderSeq || memberMobileTab !== 'orders' || memberMobileView === 'orderDetail') {
            return;
        }
        page.innerHTML = '<h2>订单</h2>' + memberOrderTabsHtml({})
            + '<div class="member-orders error">订单加载失败</div>';
    }
}

async function memberLoadOrderListMore() {
    if (memberOrderListLoadingMore || !memberOrderListHasMore) {
        return;
    }
    if (memberMobileTab !== 'orders' || memberMobileView === 'orderDetail') {
        return;
    }
    const seq = memberOrdersRenderSeq;
    const nextPage = memberOrderListPageNo + 1;
    memberOrderListLoadingMore = true;
    memberOrderListHint('加载中...');
    let loadedOk = false;
    try {
        const list = await memberRequest('/demo/orders/list', {
            tab: memberOrderListTab,
            pageNo: nextPage,
            pageSize: MEMBER_ORDER_PAGE_SIZE
        });
        if (seq !== memberOrdersRenderSeq || memberMobileTab !== 'orders' || memberMobileView === 'orderDetail') {
            return;
        }
        const box = document.querySelector('#memberPhonePage .member-orders');
        const incoming = (list && list.items) ? list.items : [];
        if (box && incoming.length) {
            box.insertAdjacentHTML('beforeend', incoming.map(memberOrderCardHtml).join(''));
        }
        memberOrderListPageNo = nextPage;
        const loaded = box ? box.querySelectorAll('.member-order-list-card').length : 0;
        memberOrderListHasMore = memberOrderListHasNext(loaded, list);
        memberOrderListHint(memberOrderListHasMore ? '继续上滑加载更多' : (loaded ? '没有更多了' : ''));
        loadedOk = true;
    } catch (e) {
        if (seq !== memberOrdersRenderSeq) {
            return;
        }
        memberOrderListHint('加载失败，上滑重试');
    } finally {
        if (seq === memberOrdersRenderSeq) {
            memberOrderListLoadingMore = false;
            if (loadedOk) {
                memberMaybeLoadMoreOrders();
            }
        }
    }
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

async function memberOrderRefresh() {
    if (!memberRequireLogin()) {
        return;
    }
    const orderId = memberOrderLastOrderId;
    if (!orderId) {
        memberAppendLog('刷新订单失败：请先在 C 端打开一笔订单');
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
