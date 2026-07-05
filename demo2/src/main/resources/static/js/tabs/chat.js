// ========== 聊天功能 ==========
const chatMessages = document.getElementById('chatMessages');
const chatForm = document.getElementById('chatForm');
const messageInput = document.getElementById('messageInput');
const sendButton = document.getElementById('sendButton');

function scrollChatMessages() {
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

function removeWelcomeMessage() {
    const welcome = document.querySelector('.welcome-message');
    if (welcome) welcome.remove();
}

function addMessage(content, isUser = false) {
    removeWelcomeMessage();
    const div = document.createElement('div');
    div.className = `message ${isUser ? 'user' : 'assistant'}`;
    const content_div = document.createElement('div');
    content_div.className = isUser ? 'message-content' : 'message-content markdown-body';
    if (isUser) {
        content_div.textContent = content;
    } else {
        content_div.innerHTML = renderMarkdown(content);
    }
    div.appendChild(content_div);
    chatMessages.appendChild(div);
    scrollChatMessages();
}

function addLoadingMessage() {
    removeWelcomeMessage();
    const div = document.createElement('div');
    div.className = 'message assistant';
    div.id = 'loadingMessage';
    const content_div = document.createElement('div');
    content_div.className = 'message-content typing';
    content_div.textContent = 'AI 正在思考';
    div.appendChild(content_div);
    chatMessages.appendChild(div);
    scrollChatMessages();
}

function removeLoadingMessage() {
    const el = document.getElementById('loadingMessage');
    if (el) el.remove();
}

async function sendMessage(message) {
    addMessage(message, true);
    addLoadingMessage();
    sendButton.disabled = true;
    messageInput.disabled = true;

    try {
        const response = await fetch('/ai/chatStream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
        });

        if (!response.ok) throw new Error('网络请求失败');
        removeLoadingMessage();

        const div = document.createElement('div');
        div.className = 'message assistant';
        const content_div = createAssistantMarkdownElement('div', 'message-content');
        div.appendChild(content_div);
        chatMessages.appendChild(div);

        const stream = createMarkdownStreamRenderer(content_div, scrollChatMessages);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            const chunk = decoder.decode(value, { stream: true });
            for (const line of chunk.split('\n').filter(l => l.trim())) {
                try {
                    const data = JSON.parse(line.replace(/^data:\s*/, '').trim());
                    if (data.response) {
                        stream.append(data.response);
                    }
                } catch (e) {}
            }
        }
        stream.flush();
    } catch (error) {
        removeLoadingMessage();
        addMessage('抱歉，发生了错误：' + error.message, false);
    } finally {
        sendButton.disabled = false;
        messageInput.disabled = false;
        messageInput.focus();
    }
}

chatForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const message = messageInput.value.trim();
    if (!message) return;
    messageInput.value = '';
    await sendMessage(message);
});

messageInput.focus();
