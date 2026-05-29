let currentSessionId = 'web-' + Date.now();
let isProcessing = false;
let isRegenerating = false;
let messagesContainer = null;
let abortController = null;
let lastUserMessage = '';
let streamingAnswerEl = null;
let streamingContent = '';
let renderDebounceTimer = null;

// Auth state
let accessToken = localStorage.getItem('accessToken');
let refreshToken = localStorage.getItem('refreshToken');
let currentUsername = localStorage.getItem('username');

// Auth helper functions
function getAuthHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (accessToken) {
        headers['Authorization'] = 'Bearer ' + accessToken;
    }
    return headers;
}

async function fetchWithAuth(url, options = {}) {
    if (!options.headers) {
        options.headers = getAuthHeaders();
    }

    let response = await fetch(url, options);

    // If 401, try to refresh token
    if (response.status === 401 && refreshToken) {
        const refreshed = await tryRefreshToken();
        if (refreshed) {
            options.headers = getAuthHeaders();
            response = await fetch(url, options);
        }
    }

    return response;
}

async function tryRefreshToken() {
    try {
        const resp = await fetch('/api/auth/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken })
        });

        if (resp.ok) {
            const data = await resp.json();
            accessToken = data.accessToken;
            localStorage.setItem('accessToken', accessToken);
            return true;
        }
    } catch (e) {}

    // Refresh failed, logout
    logout();
    return false;
}

function showAuthModal() {
    document.getElementById('authModal').style.display = 'flex';
}

function hideAuthModal() {
    document.getElementById('authModal').style.display = 'none';
}

function switchAuthTab(tab) {
    document.querySelectorAll('.auth-tab').forEach(t => t.classList.remove('active'));
    document.querySelector(`.auth-tab:${tab === 'login' ? 'first-child' : 'last-child'}`).classList.add('active');
    document.getElementById('loginForm').style.display = tab === 'login' ? 'flex' : 'none';
    document.getElementById('registerForm').style.display = tab === 'register' ? 'flex' : 'none';
    document.getElementById('authError').style.display = 'none';
}

function showAuthError(message) {
    const errorEl = document.getElementById('authError');
    errorEl.textContent = message;
    errorEl.style.display = 'block';
}

async function handleLogin(event) {
    event.preventDefault();
    const username = document.getElementById('loginUsername').value;
    const password = document.getElementById('loginPassword').value;

    try {
        const resp = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password })
        });

        const data = await resp.json();
        if (resp.ok) {
            accessToken = data.accessToken;
            refreshToken = data.refreshToken;
            currentUsername = data.username;
            localStorage.setItem('accessToken', accessToken);
            localStorage.setItem('refreshToken', refreshToken);
            localStorage.setItem('username', currentUsername);
            hideAuthModal();
            updateUserInfo();
            loadSessions();
        } else {
            showAuthError(data.error || '登录失败');
        }
    } catch (e) {
        showAuthError('网络错误');
    }
}

async function handleRegister(event) {
    event.preventDefault();
    const username = document.getElementById('regUsername').value;
    const email = document.getElementById('regEmail').value;
    const password = document.getElementById('regPassword').value;
    const passwordConfirm = document.getElementById('regPasswordConfirm').value;

    if (password !== passwordConfirm) {
        showAuthError('两次密码不一致');
        return;
    }

    try {
        const resp = await fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password, email })
        });

        const data = await resp.json();
        if (resp.ok) {
            accessToken = data.accessToken;
            refreshToken = data.refreshToken;
            currentUsername = data.username;
            localStorage.setItem('accessToken', accessToken);
            localStorage.setItem('refreshToken', refreshToken);
            localStorage.setItem('username', currentUsername);
            hideAuthModal();
            updateUserInfo();
            loadSessions();
        } else {
            showAuthError(data.error || '注册失败');
        }
    } catch (e) {
        showAuthError('网络错误');
    }
}

function logout() {
    accessToken = null;
    refreshToken = null;
    currentUsername = null;
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('username');
    showAuthModal();
}

function updateUserInfo() {
    const userInfo = document.getElementById('userInfo');
    const usernameDisplay = document.getElementById('usernameDisplay');
    if (currentUsername) {
        userInfo.style.display = 'flex';
        usernameDisplay.textContent = currentUsername;
    } else {
        userInfo.style.display = 'none';
    }
}

async function init() {
    const saved = localStorage.getItem('theme') || 'dark';
    document.documentElement.dataset.theme = saved;
    updateThemeIcon(saved);

    messagesContainer = document.querySelector('.messages-inner');
    const textarea = document.getElementById('input');
    textarea.addEventListener('input', () => {
        autoResize(textarea);
        if (!isProcessing) document.getElementById('sendBtn').disabled = !textarea.value.trim();
    });

    // Scroll listener for scroll-to-bottom button
    const messagesDiv = document.getElementById('messages');
    messagesDiv.addEventListener('scroll', () => {
        const btn = document.getElementById('scrollBottom');
        const atBottom = messagesDiv.scrollTop + messagesDiv.clientHeight >= messagesDiv.scrollHeight - 100;
        btn.classList.toggle('visible', !atBottom);
    });

    // Keyboard shortcut: Ctrl+/ or Escape to focus input
    document.addEventListener('keydown', (e) => {
        if ((e.ctrlKey && e.key === '/') || (e.key === 'Escape' && !isProcessing)) {
            e.preventDefault();
            textarea.focus();
        }
    });

    // Check auth state — validate token before hiding modal
    if (accessToken) {
        try {
            const resp = await fetchWithAuth('/api/sessions');
            if (resp.ok) {
                hideAuthModal();
                updateUserInfo();
                const sessions = await resp.json();
                renderSessions(sessions);
            } else {
                showAuthModal();
            }
        } catch (e) {
            showAuthModal();
        }
    } else {
        showAuthModal();
    }
}

// ============ Theme ============
function toggleTheme() {
    const current = document.documentElement.dataset.theme || 'dark';
    const next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    localStorage.setItem('theme', next);
    updateThemeIcon(next);
}

function updateThemeIcon(theme) {
    const icon = document.getElementById('themeIcon');
    if (theme === 'light') {
        icon.innerHTML = '<circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>';
    } else {
        icon.innerHTML = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>';
    }
}

// ============ Mobile Sidebar ============
function toggleSidebar() {
    document.querySelector('.sidebar').classList.toggle('open');
    document.getElementById('sidebarOverlay').classList.toggle('open');
}

function closeSidebar() {
    document.querySelector('.sidebar').classList.remove('open');
    document.getElementById('sidebarOverlay').classList.remove('open');
}

// ============ Sessions ============
function newSession() {
    currentSessionId = 'web-' + Date.now();
    messagesContainer.innerHTML = '';
    showWelcome();
    document.getElementById('chatTitle').textContent = 'New Conversation';
    loadSessions();
    closeSidebar();
}

function showWelcome() {
    messagesContainer.innerHTML = `
        <div class="welcome" id="welcome">
            <div class="welcome-icon">M</div>
            <h2>MiMo Code Agent</h2>
            <p>Powered by Xiaomi MiMo v2.5 Pro. I can help you read, write, and search code, execute commands, manage git, and more.</p>
            <div class="suggestions">
                <button class="suggestion" onclick="useSuggestion(this)">List files in current directory</button>
                <button class="suggestion" onclick="useSuggestion(this)">Show git status</button>
                <button class="suggestion" onclick="useSuggestion(this)">Find all Java files</button>
                <button class="suggestion" onclick="useSuggestion(this)">Help me write a function</button>
            </div>
        </div>`;
}

function useSuggestion(btn) {
    document.getElementById('input').value = btn.textContent;
    document.getElementById('sendBtn').disabled = false;
    sendMessage();
}

function renderSessions(sessions) {
    const list = document.getElementById('sessionList');
    list.innerHTML = '';
    for (const s of sessions) {
        const div = document.createElement('div');
        div.className = `session-item ${s === currentSessionId ? 'active' : ''}`;
        div.dataset.sessionId = s;
        div.innerHTML = `
            <svg class="session-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
            <span class="session-name">${escapeHtml(s.substring(0, 24))}</span>
            <button class="session-delete" title="Delete">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>`;
        div.addEventListener('click', () => switchSession(s));
        div.querySelector('.session-delete').addEventListener('click', e => { e.stopPropagation(); deleteSession(s); });
        list.appendChild(div);
    }
}

async function loadSessions() {
    try {
        const resp = await fetchWithAuth('/api/sessions');
        const sessions = await resp.json();
        renderSessions(sessions);
    } catch (e) {}
}

async function deleteSession(id) {
    try {
        await fetchWithAuth(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' });
        if (id === currentSessionId) newSession();
        loadSessions();
    } catch (e) {}
}

async function switchSession(id) {
    currentSessionId = id;
    messagesContainer.innerHTML = '';
    addSystemMessage('Loading messages...');
    document.getElementById('chatTitle').textContent = id.substring(0, 30);
    loadSessions();
    closeSidebar();

    try {
        const resp = await fetchWithAuth(`/api/sessions/${encodeURIComponent(id)}/messages`);
        if (resp.ok) {
            const messages = await resp.json();
            messagesContainer.innerHTML = '';
            if (messages.length === 0) {
                showWelcome();
            } else {
                messages.forEach(m => {
                    if (m.role === 'tool') {
                        // 渲染工具调用结果
                        if (Array.isArray(m.content)) {
                            m.content.forEach(tool => {
                                addToolResult(tool.name, tool.result, false);
                            });
                        }
                    } else if (m.role === 'assistant' && m.toolCalls) {
                        // 渲染带工具调用的助手消息
                        addMessage(m.role, m.content);
                        m.toolCalls.forEach(tc => {
                            addToolCall(tc.name, tc.arguments);
                        });
                    } else {
                        addMessage(m.role, m.content);
                    }
                });
            }
        } else {
            messagesContainer.innerHTML = '';
            showWelcome();
        }
    } catch (e) {
        messagesContainer.innerHTML = '';
        showWelcome();
    }
}

function handleKey(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (document.getElementById('input').value.trim()) sendMessage();
    }
}

// ============ Send / Stop ============
async function sendMessage() {
    const input = document.getElementById('input');
    const message = input.value.trim();
    if (!message || isProcessing) return;

    isProcessing = true;
    lastUserMessage = message;
    abortController = new AbortController();

    // Switch to stop mode
    const sendBtn = document.getElementById('sendBtn');
    sendBtn.disabled = false;
    sendBtn.classList.add('stop-mode');
    sendBtn.onclick = stopGeneration;
    sendBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>';

    const welcome = document.getElementById('welcome');
    if (welcome) welcome.remove();

    addMessage('user', message);
    document.getElementById('chatTitle').textContent = message.substring(0, 40) + (message.length > 40 ? '...' : '');

    const typingEl = addTyping();
    input.value = '';
    autoResize(input);

    try {
        const regenerate = isRegenerating;
        isRegenerating = false;
        const response = await fetchWithAuth('/api/chat', {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({ message, sessionId: currentSessionId, regenerate }),
            signal: abortController.signal
        });

        typingEl.remove();

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Server error: ${response.status} ${errorText}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop();
            for (const line of lines) {
                if (line.startsWith('data:')) {
                    try {
                        handleSSEEvent(JSON.parse(line.substring(5).trim()));
                    } catch (e) {}
                }
            }
        }

        // Process remaining buffer after stream ends
        if (buffer.trim()) {
            for (const line of buffer.split('\n')) {
                if (line.startsWith('data:')) {
                    try {
                        handleSSEEvent(JSON.parse(line.substring(5).trim()));
                    } catch (e) {}
                }
            }
        }

        // Safety: finalize answer if stream ended without DONE event
        finalizeAnswer();
    } catch (error) {
        typingEl.remove();
        if (error.name === 'AbortError') {
            addSystemMessage('Generation stopped');
            finalizeAnswer();
        } else {
            addSystemMessage('Error: ' + error.message);
            finalizeAnswer();
        }
    } finally {
        resetSendButton();
        loadSessions();
    }
}

function stopGeneration() {
    if (abortController) {
        abortController.abort();
        abortController = null;
    }
}

function resetSendButton() {
    isProcessing = false;
    abortController = null;
    const sendBtn = document.getElementById('sendBtn');
    const input = document.getElementById('input');
    sendBtn.classList.remove('stop-mode');
    sendBtn.disabled = !input.value.trim();
    sendBtn.onclick = sendMessage;
    sendBtn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>';
    input.focus();
}

// ============ SSE Events ============
function handleSSEEvent(data) {
    switch (data.type) {
        case 'TOOL_CALL': addToolCall(data.toolName, data.content); break;
        case 'TOOL_RESULT': addToolResult(data.toolName, data.content, data.isError); break;
        case 'THINKING': addThinking(data.content); break;
        case 'ANSWER_CHUNK': appendAnswerChunk(data.content); break;
        case 'DONE': finalizeAnswer(); break;
        case 'ERROR': addSystemMessage(data.content, true); finalizeAnswer(); break;
    }
}

// ============ Message Rendering ============
function addMessage(role, content) {
    const div = document.createElement('div');
    div.className = `msg ${role}`;
    const avatarText = role === 'user' ? 'U' : 'M';
    div.innerHTML = `
        <div class="msg-actions">
            <button class="msg-action-btn" onclick="copyMessage(this)" title="Copy">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
            </button>
            ${role === 'user' ? `<button class="msg-action-btn" onclick="regenerate()" title="Regenerate">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
            </button>` : ''}
        </div>
        <div class="msg-avatar">${avatarText}</div>
        <div class="msg-body">
            <div class="msg-role">${role === 'user' ? 'You' : 'MiMo'}</div>
            <div class="msg-content">${role === 'assistant' ? renderMarkdown(content) : escapeHtml(content)}</div>
        </div>`;
    messagesContainer.appendChild(div);
    if (role === 'assistant') enhanceCodeBlocks(div);
    scrollToBottom();
}

function appendAnswerChunk(chunk) {
    if (!streamingAnswerEl) {
        streamingAnswerEl = document.createElement('div');
        streamingAnswerEl.className = 'msg assistant';
        streamingAnswerEl.innerHTML = `
            <div class="msg-actions">
                <button class="msg-action-btn" onclick="copyMessage(this)" title="Copy">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                </button>
            </div>
            <div class="msg-avatar">M</div>
            <div class="msg-body">
                <div class="msg-role">MiMo</div>
                <div class="msg-content streaming-cursor"></div>
            </div>`;
        messagesContainer.appendChild(streamingAnswerEl);
        streamingContent = '';
    }
    streamingContent += chunk;

    // Append raw text immediately for typewriter effect (preserves existing HTML)
    const contentEl = streamingAnswerEl.querySelector('.msg-content');
    contentEl.insertAdjacentText('beforeend', chunk);
    scrollToBottom();

    // Debounce markdown rendering
    clearTimeout(renderDebounceTimer);
    renderDebounceTimer = setTimeout(() => {
        contentEl.innerHTML = renderMarkdown(streamingContent);
        enhanceCodeBlocks(streamingAnswerEl);
    }, 100);
}

function finalizeAnswer() {
    clearTimeout(renderDebounceTimer);
    if (streamingAnswerEl) {
        const contentEl = streamingAnswerEl.querySelector('.msg-content');
        contentEl.innerHTML = renderMarkdown(streamingContent);
        contentEl.classList.remove('streaming-cursor');
        enhanceCodeBlocks(streamingAnswerEl);
        streamingAnswerEl = null;
        streamingContent = '';
    }
}

// ============ Tool Calls ============
function addToolCall(name, args) {
    const div = document.createElement('div');
    div.className = 'tool-call';
    div.innerHTML = `
        <div class="tool-header" onclick="this.parentElement.classList.toggle('expanded')">
            <span class="tool-icon running">⚙</span>
            <span class="tool-name">${escapeHtml(name)}</span>
            <span class="tool-status running">Running</span>
            <span class="tool-chevron">▶</span>
        </div>
        <div class="tool-body">${escapeHtml(args)}</div>`;
    messagesContainer.appendChild(div);
    scrollToBottom();
}

function addToolResult(name, output, isError) {
    const div = document.createElement('div');
    div.className = 'tool-call';
    div.innerHTML = `
        <div class="tool-header" onclick="this.parentElement.classList.toggle('expanded')">
            <span class="tool-icon">${isError ? '✗' : '✓'}</span>
            <span class="tool-name">${escapeHtml(name)}</span>
            <span class="tool-status ${isError ? 'error' : 'success'}">${isError ? 'Error' : 'Done'}</span>
            <span class="tool-chevron">▶</span>
        </div>
        <div class="tool-body">${escapeHtml(output).substring(0, 2000)}${output.length > 2000 ? '\n... (truncated)' : ''}</div>`;
    messagesContainer.appendChild(div);
    scrollToBottom();
}

function addThinking(content) {
    let existing = messagesContainer.querySelector('.thinking:last-of-type');
    if (!existing) {
        existing = document.createElement('div');
        existing.className = 'thinking';
        messagesContainer.appendChild(existing);
    }
    existing.textContent += content;
    scrollToBottom();
}

function addSystemMessage(text, isError) {
    const div = document.createElement('div');
    div.className = 'msg-system' + (isError ? ' error' : '');
    div.textContent = text;
    messagesContainer.appendChild(div);
    scrollToBottom();
}

function addTyping() {
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.innerHTML = `
        <div class="msg-avatar">M</div>
        <div class="msg-body">
            <div class="msg-role">MiMo</div>
            <div class="typing"><span></span><span></span><span></span></div>
        </div>`;
    messagesContainer.appendChild(div);
    scrollToBottom();
    return div;
}

// ============ Message Actions ============
function copyMessage(btn) {
    const msgBody = btn.closest('.msg').querySelector('.msg-content');
    navigator.clipboard.writeText(msgBody.textContent).then(() => {
        btn.classList.add('copied');
        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>';
        setTimeout(() => {
            btn.classList.remove('copied');
            btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
        }, 2000);
    });
}

function regenerate() {
    if (!lastUserMessage || isProcessing) return;
    isRegenerating = true;
    document.getElementById('input').value = lastUserMessage;
    // Remove the last user message and assistant response
    const msgs = messagesContainer.querySelectorAll('.msg');
    if (msgs.length >= 2) {
        const last = msgs[msgs.length - 1];
        const secondLast = msgs[msgs.length - 2];
        if (last.classList.contains('assistant')) last.remove();
        if (secondLast.classList.contains('user')) secondLast.remove();
    }
    sendMessage();
}

// ============ Code Block Enhancements ============
function enhanceCodeBlocks(container) {
    container.querySelectorAll('pre:not([data-enhanced])').forEach(pre => {
        pre.dataset.enhanced = 'true';

        const code = pre.querySelector('code');
        if (!code) return;

        // Extract language from data-lang attribute set by renderMarkdown
        let lang = pre.dataset.lang || '';
        const text = code.textContent;

        // Add line numbers
        const lines = text.split('\n');
        // Remove trailing empty line if exists
        if (lines[lines.length - 1] === '') lines.pop();
        code.innerHTML = lines.map((line, i) =>
            `<span class="code-line" data-line="${i + 1}">${escapeHtml(line)}</span>`
        ).join('\n');

        // Build code header
        const header = document.createElement('div');
        header.className = 'code-header';

        const langLabel = document.createElement('span');
        langLabel.className = 'code-lang';
        langLabel.textContent = lang || 'code';

        const copyBtn = document.createElement('button');
        copyBtn.className = 'copy-btn';
        copyBtn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg> Copy';
        copyBtn.onclick = (e) => {
            e.stopPropagation();
            navigator.clipboard.writeText(text).then(() => {
                copyBtn.classList.add('copied');
                copyBtn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> Copied!';
                setTimeout(() => {
                    copyBtn.classList.remove('copied');
                    copyBtn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg> Copy';
                }, 2000);
            });
        };

        header.appendChild(langLabel);
        header.appendChild(copyBtn);
        pre.insertBefore(header, code);
    });
}

// ============ Markdown Renderer ============
function renderMarkdown(text) {
    let html = escapeHtml(text);

    // Code blocks with language (must be first to prevent inner processing)
    const codeBlocks = [];
    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (match, lang, code) => {
        const placeholder = `__CODE_BLOCK_${codeBlocks.length}__`;
        codeBlocks.push(`<pre data-lang="${lang}"><code>${code}</code></pre>`);
        return placeholder;
    });

    // Inline code
    const inlineCodes = [];
    html = html.replace(/`([^`]+)`/g, (match, code) => {
        const placeholder = `__INLINE_CODE_${inlineCodes.length}__`;
        inlineCodes.push(`<code>${code}</code>`);
        return placeholder;
    });

    // Tables
    html = html.replace(/^\|(.+)\|\s*\n\|[-\s|:]+\|\s*\n((?:\|.+\|\s*\n?)*)/gm, (match, header, body) => {
        const headers = header.split('|').map(h => h.trim()).filter(h => h);
        const rows = body.trim().split('\n').map(row =>
            row.split('|').map(cell => cell.trim()).filter(cell => cell)
        );

        let table = '<table><thead><tr>';
        headers.forEach(h => table += `<th>${h}</th>`);
        table += '</tr></thead><tbody>';
        rows.forEach(row => {
            table += '<tr>';
            row.forEach(cell => table += `<td>${cell}</td>`);
            table += '</tr>';
        });
        table += '</tbody></table>';
        return table;
    });

    // Headers
    html = html.replace(/^###### (.*$)/gm, '<h6>$1</h6>');
    html = html.replace(/^##### (.*$)/gm, '<h5>$1</h5>');
    html = html.replace(/^#### (.*$)/gm, '<h4>$1</h4>');
    html = html.replace(/^### (.*$)/gm, '<h3>$1</h3>');
    html = html.replace(/^## (.*$)/gm, '<h2>$1</h2>');
    html = html.replace(/^# (.*$)/gm, '<h1>$1</h1>');

    // Horizontal rule
    html = html.replace(/^---+$/gm, '<hr>');

    // Blockquote
    html = html.replace(/^> (.*$)/gm, '<blockquote>$1</blockquote>');

    // Bold and italic
    html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*(.*?)\*/g, '<em>$1</em>');
    html = html.replace(/~~(.*?)~~/g, '<del>$1</del>');

    // Links
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    html = html.replace(/(https?:\/\/[^\s<]+)/g, '<a href="$1" target="_blank" rel="noopener">$1</a>');

    // Unordered lists
    html = html.replace(/^(\s*)[-*] (.*$)/gm, '$1<li>$2</li>');
    html = html.replace(/((?:<li>.*<\/li>\s*)+)/g, '<ul>$1</ul>');

    // Ordered lists
    html = html.replace(/^\d+\. (.*$)/gm, '<oli>$1</oli>');
    html = html.replace(/((?:<oli>.*<\/oli>\s*)+)/g, (match) => {
        return '<ol>' + match.replace(/<oli>/g, '<li>').replace(/<\/oli>/g, '</li>') + '</ol>';
    });

    // Line breaks (but not inside lists)
    html = html.replace(/\n/g, '<br>');
    html = html.replace(/<\/ul><br><ul>/g, '');
    html = html.replace(/<br><\/ul>/g, '</ul>');
    html = html.replace(/<ul><br>/g, '<ul>');
    html = html.replace(/<\/ol><br><ol>/g, '');
    html = html.replace(/<br><\/ol>/g, '</ol>');
    html = html.replace(/<ol><br>/g, '<ol>');

    // Restore code blocks
    codeBlocks.forEach((block, i) => {
        html = html.replace(`__CODE_BLOCK_${i}__`, block);
    });
    inlineCodes.forEach((code, i) => {
        html = html.replace(`__INLINE_CODE_${i}__`, code);
    });

    return html;
}

function escapeHtml(text) {
    const d = document.createElement('div');
    d.textContent = text;
    return d.innerHTML;
}

function scrollToBottom() {
    const m = document.getElementById('messages');
    m.scrollTop = m.scrollHeight;
    document.getElementById('scrollBottom').classList.remove('visible');
}

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 200) + 'px';
}

init();
