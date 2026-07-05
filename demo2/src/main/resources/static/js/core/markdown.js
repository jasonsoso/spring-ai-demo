(function initMarkedDefaults() {
    if (typeof marked === 'undefined' || typeof marked.use !== 'function') {
        return;
    }
    marked.use({
        gfm: true,
        breaks: true,
        renderer: {
            link({ href, title, text }) {
                const safeHref = href || '';
                const titleAttr = title ? ` title="${title}"` : '';
                return `<a href="${safeHref}" target="_blank" rel="noopener noreferrer"${titleAttr}>${text}</a>`;
            }
        }
    });
})();

function normalizeMarkdownTables(text) {
    return text.replace(/\s*\|\|\s*/g, '\n| ');
}

function sanitizeMarkdownHtml(html) {
    return DOMPurify.sanitize(html, {
        USE_PROFILES: { html: true },
        ADD_TAGS: ['input'],
        ADD_ATTR: ['target', 'rel', 'type', 'checked', 'disabled']
    });
}

function wrapMarkdownTables(html) {
    return html.replace(/<table\b/g, '<div class="table-wrap"><table').replace(/<\/table>/g, '</table></div>');
}

function renderMarkdown(text) {
    if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') {
        const div = document.createElement('div');
        div.textContent = text || '';
        return div.innerHTML;
    }
    const normalized = normalizeMarkdownTables(text || '');
    const rawHtml = marked.parse(normalized);
    return wrapMarkdownTables(sanitizeMarkdownHtml(rawHtml));
}

function debounce(fn, delayMs) {
    let timer;
    return function (...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), delayMs);
    };
}

/** 流式对话：累积文本并 debounce 渲染 Markdown */
function createMarkdownStreamRenderer(element, onUpdate) {
    let fullText = '';
    const paint = () => {
        element.innerHTML = renderMarkdown(fullText);
        if (typeof onUpdate === 'function') {
            onUpdate();
        }
    };
    const renderDebounced = debounce(paint, 150);
    return {
        append(chunk) {
            if (!chunk) return;
            fullText += chunk;
            renderDebounced();
        },
        flush() {
            paint();
        },
        setPlainError(message) {
            element.classList.remove('markdown-body');
            element.textContent = message;
        }
    };
}

/** 助手消息容器：带 markdown-body 类 */
function createAssistantMarkdownElement(tagName, className) {
    const el = document.createElement(tagName || 'div');
    el.className = (className ? className + ' ' : '') + 'markdown-body';
    return el;
}
