// ========== 会员 C 端 Auth Sheet ==========
const MemberAuth = (function () {
    let mode = 'login';
    let isOpenState = false;
    let onSuccessCallback = null;
    let overlay = null;

    function ensureOverlay() {
        const screen = document.querySelector('.member-phone-screen');
        if (!screen) {
            return null;
        }
        if (overlay) {
            return overlay;
        }

        overlay = document.createElement('div');
        overlay.id = 'memberAuthOverlay';
        overlay.className = 'member-auth-overlay';
        overlay.innerHTML =
            '<div class="member-auth-sheet" role="dialog" aria-modal="true">' +
            '<div class="member-auth-handle"></div>' +
            '<button type="button" class="member-auth-close" aria-label="关闭">×</button>' +
            '<h3 class="member-auth-title">登录</h3>' +
            '<div class="member-auth-form"></div>' +
            '<button type="button" class="member-auth-submit btn btn-primary">登录</button>' +
            '<p class="member-auth-switch"></p>' +
            '</div>';

        overlay.addEventListener('click', close);
        overlay.querySelector('.member-auth-sheet').addEventListener('click', function (event) {
            event.stopPropagation();
        });
        overlay.querySelector('.member-auth-close').addEventListener('click', close);
        overlay.querySelector('.member-auth-submit').addEventListener('click', submit);
        screen.appendChild(overlay);
        return overlay;
    }

    function sheetEl() {
        return overlay ? overlay.querySelector('.member-auth-sheet') : null;
    }

    function readFields() {
        const phoneEl = document.getElementById('memberAuthPhone');
        const passwordEl = document.getElementById('memberAuthPassword');
        const avatarEl = document.getElementById('memberAuthAvatar');
        return {
            phone: phoneEl ? phoneEl.value.trim() : '',
            password: passwordEl ? passwordEl.value : '',
            avatarUrl: avatarEl ? avatarEl.value.trim() : ''
        };
    }

    function renderFields() {
        const form = overlay.querySelector('.member-auth-form');
        const saved = readFields();

        let html =
            '<input id="memberAuthPhone" type="tel" placeholder="请输入手机号" autocomplete="username"' +
            ' value="' + memberEscapeHtml(saved.phone) + '">' +
            '<input id="memberAuthPassword" type="password" placeholder="请输入密码"' +
            ' autocomplete="' + (mode === 'login' ? 'current-password' : 'new-password') + '"' +
            ' value="' + memberEscapeHtml(saved.password) + '">';

        if (mode === 'register') {
            html += '<input id="memberAuthAvatar" placeholder="头像 URL（可选）"' +
                ' value="' + memberEscapeHtml(saved.avatarUrl) + '">';
        }

        form.innerHTML = html;
        overlay.querySelector('.member-auth-title').textContent = mode === 'login' ? '登录' : '注册';
        overlay.querySelector('.member-auth-submit').textContent = mode === 'login' ? '登录' : '注册';

        const switchBox = overlay.querySelector('.member-auth-switch');
        if (mode === 'login') {
            switchBox.innerHTML = '<button type="button">还没有账号？去注册</button>';
            switchBox.querySelector('button').addEventListener('click', function () {
                setMode('register');
            });
        } else {
            switchBox.innerHTML = '<button type="button">已有账号？去登录</button>';
            switchBox.querySelector('button').addEventListener('click', function () {
                setMode('login');
            });
        }
    }

    function setMode(nextMode) {
        mode = nextMode === 'register' ? 'register' : 'login';
        renderFields();
        const phoneEl = document.getElementById('memberAuthPhone');
        if (phoneEl) {
            phoneEl.focus();
        }
    }

    function focusFirstField() {
        const phoneEl = document.getElementById('memberAuthPhone');
        if (phoneEl) {
            phoneEl.focus();
        }
    }

    async function submit() {
        const input = readFields();
        if (!input.phone) {
            memberShowError('请输入手机号');
            return;
        }
        if (!input.password) {
            memberShowError('请输入密码');
            return;
        }

        if (mode === 'login') {
            try {
                const data = await memberRequest('/demo/members/login', {
                    phone: input.phone,
                    password: input.password
                });
                memberToken = data.token;
                memberProfile = data;
                memberSessionDeleted = false;
                localStorage.setItem(MEMBER_TOKEN_STORAGE_KEY, memberToken);
                memberAppendLog('登录成功：' + data.phone);
                memberRender();
                const callback = onSuccessCallback;
                close();
                if (callback) {
                    callback();
                }
            } catch (e) {
                memberAppendLog('登录失败：' + e.message);
            }
            return;
        }

        try {
            const data = await memberRequest('/demo/members/register', {
                phone: input.phone,
                password: input.password,
                avatarUrl: input.avatarUrl
            });
            memberAppendLog('注册成功：' + JSON.stringify(data));
            setMode('login');
            memberShowError('注册成功，请登录');
        } catch (e) {
            memberAppendLog('注册失败：' + e.message);
        }
    }

    function open(options) {
        options = options || {};
        if (!ensureOverlay()) {
            return;
        }
        mode = options.mode === 'register' ? 'register' : 'login';
        onSuccessCallback = typeof options.onSuccess === 'function' ? options.onSuccess : null;
        renderFields();
        overlay.classList.add('open');
        isOpenState = true;
        requestAnimationFrame(function () {
            overlay.classList.add('visible');
            focusFirstField();
        });
    }

    function close() {
        if (!overlay) {
            return;
        }
        overlay.classList.remove('visible');
        isOpenState = false;
        onSuccessCallback = null;
        window.setTimeout(function () {
            if (!isOpenState) {
                overlay.classList.remove('open');
            }
        }, 280);
    }

    function isOpen() {
        return isOpenState;
    }

    return {
        open: open,
        close: close,
        isOpen: isOpen
    };
})();
