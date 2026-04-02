// TinyClaw Web Console - App

class TinyClawConsole {
    constructor() {
        this.currentPage = 'chat';
        this.chatSessionId = localStorage.getItem('tinyclaw_chat_session') || 'web:default';
        this.allSessions = [];
        this.currentSessionPage = 1;
        this.authToken = localStorage.getItem('tinyclaw_token') || null;
        this.init();
    }

    init() {
        // 配置 Markdown 渲染：不开启 breaks，避免列表等块级元素产生多余 <br> 间距
        // 流式渲染阶段用 CSS white-space: pre-wrap 处理换行，finalizeCurrentText 后由 marked 正确渲染
        if (typeof marked !== 'undefined') {
            marked.setOptions({ breaks: false });
        }
        
        this.bindNavigation();
        this.bindChat();
        this.bindModal();
        this.bindLogin();
        this.checkAuthAndInit();
    }
    
    // ==================== Authentication ====================
    
    bindLogin() {
        const loginBtn = document.getElementById('loginBtn');
        const usernameInput = document.getElementById('loginUsername');
        const passwordInput = document.getElementById('loginPassword');
        
        loginBtn.addEventListener('click', () => this.doLogin());
        
        passwordInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') this.doLogin();
        });
        usernameInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') passwordInput.focus();
        });
    }
    
    async checkAuthAndInit() {
        try {
            const response = await this.authFetch('/api/auth/check');
            if (response.ok) {
                const data = await response.json();
                if (data.authEnabled === false) {
                    // 认证未启用，直接进入
                    this.hideLoginOverlay();
                    this.loadInitialPage();
                    return;
                }
                // token 有效
                this.hideLoginOverlay();
                this.loadInitialPage();
            } else {
                // 需要登录
                this.showLoginOverlay();
            }
        } catch (error) {
            // 网络错误等，尝试直接加载
            this.hideLoginOverlay();
            this.loadInitialPage();
        }
    }
    
    async doLogin() {
        const username = document.getElementById('loginUsername').value.trim();
        const password = document.getElementById('loginPassword').value;
        const errorDiv = document.getElementById('loginError');
        
        if (!username || !password) {
            errorDiv.textContent = 'Please enter username and password';
            errorDiv.style.display = 'block';
            return;
        }
        
        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            
            const data = await response.json();
            
            if (response.ok && data.success) {
                this.authToken = data.token;
                localStorage.setItem('tinyclaw_token', data.token);
                errorDiv.style.display = 'none';
                this.hideLoginOverlay();
                this.loadInitialPage();
            } else {
                errorDiv.textContent = data.error || 'Invalid username or password';
                errorDiv.style.display = 'block';
                document.getElementById('loginPassword').value = '';
                document.getElementById('loginPassword').focus();
            }
        } catch (error) {
            errorDiv.textContent = 'Connection failed. Please try again.';
            errorDiv.style.display = 'block';
        }
    }
    
    showLoginOverlay() {
        document.getElementById('loginOverlay').classList.add('active');
        setTimeout(() => document.getElementById('loginUsername').focus(), 100);
    }
    
    hideLoginOverlay() {
        document.getElementById('loginOverlay').classList.remove('active');
    }
    
    /**
     * 带认证的 fetch 封装。自动附加 Authorization 头，
     * 收到 401 时弹出登录弹窗。
     */
    async authFetch(url, options = {}) {
        if (this.authToken) {
            options.headers = options.headers || {};
            options.headers['Authorization'] = 'Basic ' + this.authToken;
        }
        const response = await fetch(url, options);
        if (response.status === 401) {
            this.authToken = null;
            localStorage.removeItem('tinyclaw_token');
            this.showLoginOverlay();
        }
        return response;
    }

    // ==================== Navigation ====================

    bindNavigation() {
        // Nav items
        document.querySelectorAll('.nav-item').forEach(item => {
            item.addEventListener('click', (e) => {
                e.preventDefault();
                const page = item.dataset.page;
                this.navigateTo(page);
            });
        });

        // Nav group collapse
        document.querySelectorAll('.nav-group-header').forEach(header => {
            header.addEventListener('click', () => {
                const group = header.parentElement;
                group.classList.toggle('collapsed');
            });
        });

        // Hash change
        window.addEventListener('hashchange', () => {
            const page = window.location.hash.slice(1) || 'chat';
            this.navigateTo(page, false);
        });
    }

    navigateTo(page, updateHash = true) {
        // Update nav
        document.querySelectorAll('.nav-item').forEach(item => {
            item.classList.toggle('active', item.dataset.page === page);
        });

        // Update page
        document.querySelectorAll('.page').forEach(p => {
            p.classList.toggle('active', p.id === `page-${page}`);
        });

        // Update title
        const titles = {
            chat: 'Chat',
            channels: 'Channels',
            sessions: 'Sessions',
            cron: 'Cron Jobs',
            workspace: 'Workspace',
            skills: 'Skills',
            mcp: 'MCP Servers',
            models: 'Models',
            environments: 'Environments',
            'token-usage': 'Token Usage'
        };
        document.getElementById('pageTitle').textContent = titles[page] || page;

        if (updateHash) {
            window.location.hash = page;
        }

        this.currentPage = page;
        this.loadPageData(page);
    }

    loadInitialPage() {
        const page = window.location.hash.slice(1) || 'chat';
        this.navigateTo(page, false);
    }

    loadPageData(page) {
        switch (page) {
            case 'chat': this.loadChatHistory(); this.loadChatSessions(); break;
            case 'channels': this.loadChannels(); break;
            case 'sessions': this.loadSessions(); break;
            case 'cron': this.loadCronJobs(); break;
            case 'workspace': this.loadWorkspaceFiles(); break;
            case 'skills': this.loadSkills(); break;
            case 'mcp': this.loadMcpServers(); break;
            case 'models': this.loadProviders(); this.loadCurrentModel(); break;
            case 'environments': this.loadAgentConfig(); break;
            case 'token-usage': this.loadTokenUsage(); break;
        }
    }

    // ==================== Chat ====================

    // 待上传的图片列表（存储 Base64 数据）
    pendingImages = [];
    // 当前正在执行的任务的 AbortController（用于中断）
    currentAbortController = null;

    bindChat() {
        const input = document.getElementById('chatInput');
        const sendBtn = document.getElementById('sendBtn');
        const newChatBtn = document.getElementById('newChatBtn');
        const uploadBtn = document.getElementById('uploadBtn');
        const imageUpload = document.getElementById('imageUpload');

        sendBtn.addEventListener('click', () => this.sendMessage());
        input.addEventListener('keydown', (e) => {
            // Ctrl+Enter (Windows/Linux) 或 Cmd+Enter (Mac) 发送消息
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                this.sendMessage();
            }
        });

        input.addEventListener('input', () => {
            input.style.height = 'auto';
            input.style.height = Math.min(input.scrollHeight, 120) + 'px';
        });

        newChatBtn.addEventListener('click', () => this.createNewChatSession());

        // 图片上传按钮
        uploadBtn.addEventListener('click', () => imageUpload.click());
        imageUpload.addEventListener('change', (e) => this.handleImageSelect(e));

        // 支持拖拽上传
        input.addEventListener('dragover', (e) => {
            e.preventDefault();
            input.classList.add('drag-over');
        });
        input.addEventListener('dragleave', () => input.classList.remove('drag-over'));
        input.addEventListener('drop', (e) => {
            e.preventDefault();
            input.classList.remove('drag-over');
            this.handleImageDrop(e);
        });

        // 支持粘贴图片
        input.addEventListener('paste', (e) => this.handleImagePaste(e));

        // 绑定初始的快捷提示语
        this.bindQuickPrompts();
    }

    /**
     * 获取欢迎界面 HTML
     */
    getWelcomeHtml() {
        return `
            <div class="chat-welcome">
                <div class="welcome-icon">🦞</div>
                <h2>Hello, how can I help you today?</h2>
                <p>I am a helpful assistant that can help you with your questions.</p>
                <div class="quick-prompts">
                    <div class="quick-prompt" data-prompt="你有哪些技能？">
                        <span class="prompt-icon">✦</span>
                        <span class="prompt-text">你有哪些技能？</span>
                        <span class="prompt-arrow">→</span>
                    </div>
                    <div class="quick-prompt" data-prompt="今天杭州天气怎么样？">
                        <span class="prompt-icon">✦</span>
                        <span class="prompt-text">今天杭州天气怎么样？</span>
                        <span class="prompt-arrow">→</span>
                    </div>
                    <div class="quick-prompt" data-prompt="帮我创建一个每小时执行的定时任务">
                        <span class="prompt-icon">✦</span>
                        <span class="prompt-text">帮我创建一个每小时执行的定时任务</span>
                        <span class="prompt-arrow">→</span>
                    </div>
                    <div class="quick-prompt" data-prompt="读取我的工作目录有哪些文件">
                        <span class="prompt-icon">✦</span>
                        <span class="prompt-text">读取我的工作目录有哪些文件</span>
                        <span class="prompt-arrow">→</span>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * 绑定快捷提示语点击事件
     */
    bindQuickPrompts() {
        document.querySelectorAll('.quick-prompt').forEach(prompt => {
            prompt.addEventListener('click', () => {
                const text = prompt.dataset.prompt;
                document.getElementById('chatInput').value = text;
                this.sendMessage();
            });
        });
    }

    /**
     * 新建聊天会话：生成新 sessionId，通知后端持久化，再更新 UI。
     * 确保刷新页面后新会话仍出现在历史列表中。
     */
    async createNewChatSession() {
        const newSessionId = 'web:' + Date.now();
        try {
            await this.authFetch('/api/sessions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionKey: newSessionId })
            });
        } catch (error) {
            console.error('Failed to create session on server:', error);
        }
        this.chatSessionId = newSessionId;
        localStorage.setItem('tinyclaw_chat_session', this.chatSessionId);
        document.getElementById('chatMessages').innerHTML = this.getWelcomeHtml();
        this.bindQuickPrompts();
        this.loadChatSessions();
        this.clearPendingImages();
    }

    // ==================== 图片上传相关 ====================

    /**
     * 处理文件选择
     */
    handleImageSelect(e) {
        const files = e.target.files;
        if (files) {
            this.processImageFiles(Array.from(files));
        }
        e.target.value = '';  // 清空输入，允许重复选择同一文件
    }

    /**
     * 处理图片拖拽
     */
    handleImageDrop(e) {
        const files = e.dataTransfer.files;
        if (files) {
            const imageFiles = Array.from(files).filter(f => f.type.startsWith('image/'));
            this.processImageFiles(imageFiles);
        }
    }

    /**
     * 处理图片粘贴
     */
    handleImagePaste(e) {
        const items = e.clipboardData?.items;
        if (!items) return;

        const imageFiles = [];
        for (const item of items) {
            if (item.type.startsWith('image/')) {
                const file = item.getAsFile();
                if (file) imageFiles.push(file);
            }
        }
        if (imageFiles.length > 0) {
            e.preventDefault();
            this.processImageFiles(imageFiles);
        }
    }

    /**
     * 处理图片文件，压缩后转换为 Base64 并添加到待上传列表。
     * 压缩策略：长边限制在 1024px 以内，JPEG 质量 0.82，可大幅降低 token 消耗。
     */
    async processImageFiles(files) {
        for (const file of files) {
            if (file.size > 10 * 1024 * 1024) {
                alert(`图片 ${file.name} 超过 10MB 限制`);
                continue;
            }

            try {
                const base64 = await this.compressImage(file);
                this.pendingImages.push({
                    data: base64,
                    name: file.name
                });
            } catch (err) {
                console.error('Failed to read image:', err);
            }
        }
        this.updateImagePreview();
    }

    /**
     * 使用 Canvas 压缩图片，将长边限制在 maxSidePx 以内并以 JPEG 格式输出。
     * 对于本身较小的图片（压缩后反而更大），回退到原始 Base64。
     *
     * @param {File} file - 图片文件
     * @param {number} maxSidePx - 长边最大像素，默认 1024
     * @param {number} quality - JPEG 压缩质量 0~1，默认 0.82
     * @returns {Promise<string>} Base64 Data URI
     */
    compressImage(file, maxSidePx = 1024, quality = 0.82) {
        return new Promise((resolve, reject) => {
            const originalUrl = URL.createObjectURL(file);
            const img = new Image();

            img.onload = () => {
                URL.revokeObjectURL(originalUrl);

                let { width, height } = img;
                if (width > maxSidePx || height > maxSidePx) {
                    if (width >= height) {
                        height = Math.round(height * maxSidePx / width);
                        width = maxSidePx;
                    } else {
                        width = Math.round(width * maxSidePx / height);
                        height = maxSidePx;
                    }
                }

                const canvas = document.createElement('canvas');
                canvas.width = width;
                canvas.height = height;
                canvas.getContext('2d').drawImage(img, 0, 0, width, height);

                const compressedDataUrl = canvas.toDataURL('image/jpeg', quality);

                // 若压缩后反而更大（如原图已是小 PNG），回退到原始编码
                const reader = new FileReader();
                reader.onload = () => {
                    const originalDataUrl = reader.result;
                    resolve(compressedDataUrl.length <= originalDataUrl.length
                        ? compressedDataUrl
                        : originalDataUrl);
                };
                reader.onerror = reject;
                reader.readAsDataURL(file);
            };

            img.onerror = () => {
                URL.revokeObjectURL(originalUrl);
                reject(new Error('Failed to load image'));
            };

            img.src = originalUrl;
        });
    }

    /**
     * 更新图片预览区域
     */
    updateImagePreview() {
        const previewDiv = document.getElementById('chatImagePreview');
        if (this.pendingImages.length === 0) {
            previewDiv.style.display = 'none';
            previewDiv.innerHTML = '';
            return;
        }

        previewDiv.style.display = 'flex';
        previewDiv.innerHTML = this.pendingImages.map((img, idx) => `
            <div class="preview-item">
                <img src="${img.data}" alt="Preview">
                <button class="preview-remove" onclick="app.removePendingImage(${idx})">×</button>
            </div>
        `).join('');
    }

    /**
     * 移除待上传的图片
     */
    removePendingImage(index) {
        this.pendingImages.splice(index, 1);
        this.updateImagePreview();
    }

    /**
     * 清空待上传图片
     */
    clearPendingImages() {
        this.pendingImages = [];
        this.updateImagePreview();
    }

    /**
     * 加载当前 session 的聊天历史。
     * 连续的 assistant 消息会合并成一个气泡，避免多轮工具调用产生的碎片感。
     */
    async loadChatHistory() {
        try {
            const response = await this.authFetch(`/api/sessions/${encodeURIComponent(this.chatSessionId)}`);
            if (!response.ok) return;
            
            const messages = await response.json();
            // 过滤出有实际内容的 user/assistant 消息
            // 注意：assistant 消息即使 content 为空，只要有 toolCallRecords 也需要保留，
            // 否则工具调用卡片会因找不到对应消息而丢失
            const visibleMessages = (messages || []).filter(msg => {
                if (msg.role === 'summary') return true; // 摘要消息始终保留
                if (msg.role !== 'user' && msg.role !== 'assistant') return false;
                const hasContent = msg.content || (msg.images && msg.images.length > 0);
                const hasToolCalls = msg.role === 'assistant' && msg.toolCallRecords && msg.toolCallRecords.length > 0;
                return hasContent || hasToolCalls;
            });
            if (visibleMessages.length === 0) return;

            // 将连续的 assistant 消息合并，减少碎片气泡
            const mergedMessages = [];
            for (const msg of visibleMessages) {
                // 清洗旧格式遗留数据：过滤掉以 {"type":"TOOL_ 开头的行（改造前后端误存的 StreamEvent JSON）
                let cleanContent = msg.content;
                if (msg.role === 'assistant' && cleanContent) {
                    const cleanedLines = cleanContent
                        .split('\n')
                        .filter(line => !line.trimStart().startsWith('{"type":"TOOL_'));
                    cleanContent = cleanedLines.join('\n').trim();
                }

                const last = mergedMessages[mergedMessages.length - 1];
                // 有工具调用记录的 assistant 消息不合并，保持独立渲染，
                // 确保工具卡片能插入在正确位置（两段文字之间）
                const lastHasToolCalls = last && last.toolCallRecords && last.toolCallRecords.length > 0;
                if (msg.role === 'assistant' && last && last.role === 'assistant' && !lastHasToolCalls) {
                    // 合并：用双换行分隔，保持段落感
                    last.content = [last.content, cleanContent].filter(Boolean).join('\n\n');
                } else {
                    mergedMessages.push({
                        role: msg.role,
                        content: cleanContent,
                        images: msg.images || [],
                        toolCallRecords: msg.toolCallRecords || []
                    });
                }
            }
            
            const messagesDiv = document.getElementById('chatMessages');
            // 清除欢迎消息，渲染历史记录
            messagesDiv.innerHTML = '';
            for (const msg of mergedMessages) {
                // 摘要消息：渲染为折叠提示卡片，告知用户前面有内容已被压缩
                if (msg.role === 'summary') {
                    const summaryDiv = document.createElement('div');
                    summaryDiv.className = 'history-summary-banner';
                    summaryDiv.innerHTML = `
                        <span class="summary-icon">📋</span>
                        <span class="summary-label">以上内容已压缩为摘要</span>
                        <details class="summary-details">
                            <summary>查看摘要</summary>
                            <div class="summary-content">${this.escapeHtml(msg.content)}</div>
                        </details>`;
                    messagesDiv.appendChild(summaryDiv);
                    continue;
                }
                this.addMessage(msg.content, msg.role, msg.images, false);
                // assistant 消息后插入工具调用卡片（历史回放）
                // 卡片必须追加到消息气泡的 .message-content 内部，与流式渲染保持一致
                if (msg.role === 'assistant' && msg.toolCallRecords && msg.toolCallRecords.length > 0) {
                    const lastMessageEl = messagesDiv.lastElementChild;
                    const contentEl = lastMessageEl ? lastMessageEl.querySelector('.message-content') : null;
                    const targetContainer = contentEl || messagesDiv;
                    for (const record of msg.toolCallRecords) {
                        this.appendHistoryToolCallCard(targetContainer, record);
                    }
                }
            }
            // 历史回放完成后滚到顶部，让用户从头阅读完整会话
            messagesDiv.scrollTop = 0;
            
            // 检查后端是否有任务正在运行（刷新页面后恢复运行状态）
            this.checkAndRestoreRunningState();
        } catch (error) {
            console.error('Failed to load chat history:', error);
        }
    }

    /**
     * 检查后端是否有任务正在运行，如果有则恢复前端的运行状态指示。
     * 用于刷新页面后恢复运行状态，避免用户误以为任务已丢失。
     */
    async checkAndRestoreRunningState() {
        try {
            const response = await this.authFetch('/api/chat/status');
            if (!response.ok) return;
            const status = await response.json();
            if (status.running) {
                const sendBtn = document.getElementById('sendBtn');
                if (sendBtn) {
                    sendBtn.classList.add('loading');
                    sendBtn.innerHTML = '⏹';
                    sendBtn.title = '点击中断任务';
                }
                // 创建一个 AbortController 以支持中断
                this.currentAbortController = new AbortController();
                // 在消息区域底部添加运行中提示
                const messagesDiv = document.getElementById('chatMessages');
                const banner = document.createElement('div');
                banner.className = 'running-task-banner';
                banner.id = 'runningTaskBanner';
                banner.innerHTML = `<span class="running-task-icon"><span class="tool-call-spinner"></span></span><span class="running-task-text">有任务正在后台运行中，SSE 连接已断开。点击停止按钮可中断任务。</span>`;
                messagesDiv.appendChild(banner);
                messagesDiv.scrollTop = messagesDiv.scrollHeight;
                // 轮询等待任务完成
                this.pollTaskCompletion();
            }
        } catch (error) {
            console.warn('Failed to check running state:', error);
        }
    }

    /**
     * 轮询后端任务状态，任务完成后恢复按钮状态并刷新历史。
     */
    async pollTaskCompletion() {
        const pollInterval = 3000;
        const poll = async () => {
            try {
                const response = await this.authFetch('/api/chat/status');
                if (!response.ok) return;
                const status = await response.json();
                if (!status.running) {
                    // 任务已完成，恢复按钮状态
                    const sendBtn = document.getElementById('sendBtn');
                    if (sendBtn) {
                        sendBtn.classList.remove('loading');
                        sendBtn.innerHTML = '➤';
                        sendBtn.title = '发送消息';
                    }
                    this.currentAbortController = null;
                    // 移除运行中提示
                    const banner = document.getElementById('runningTaskBanner');
                    if (banner) banner.remove();
                    // 重新加载历史以获取最新的完整回复
                    this.loadChatHistory();
                    return;
                }
                // 继续轮询
                setTimeout(poll, pollInterval);
            } catch (error) {
                console.warn('Poll task status failed:', error);
                setTimeout(poll, pollInterval);
            }
        };
        setTimeout(poll, pollInterval);
    }

    /**
     * 在历史回放时，将一条工具调用记录渲染为卡片并追加到消息容器。
     * 复用流式渲染时的 tool-call-card 样式，保持视觉一致性。
     *
     * @param {HTMLElement} container - 消息容器（chatMessages div）
     * @param {Object} record - 工具调用记录 { toolName, argsSummary, resultSummary, success }
     */
    appendHistoryToolCallCard(container, record) {
        const toolName = record.toolName || 'unknown';
        const argsSummary = record.argsSummary || '';
        const resultSummary = record.resultSummary || '';
        const success = record.success !== false;

        const card = document.createElement('div');
        card.className = 'tool-call-card';

        const argsSection = argsSummary
            ? `<div class="tool-call-section">
                 <div class="tool-call-section-label">参数</div>
                 <div class="tool-call-args">${this.escapeHtml(argsSummary)}</div>
               </div>`
            : '';

        const resultSection = resultSummary
            ? `<div class="tool-call-section">
                 <div class="tool-call-section-label">结果</div>
                 <div class="tool-call-result${success ? '' : ' error-result'}">${this.escapeHtml(resultSummary)}</div>
               </div>`
            : '';

        const statusClass = success ? 'success' : 'error';
        const statusText = success ? '✅ 完成' : '❌ 失败';

        card.innerHTML = `
            <div class="tool-call-header" onclick="this.parentElement.classList.toggle('expanded')">
                <span class="tool-call-icon">🔧</span>
                <span class="tool-call-name">${this.escapeHtml(toolName)}</span>
                <span class="tool-call-status ${statusClass}">${statusText}</span>
                <span class="tool-call-toggle">▼</span>
            </div>
            <div class="tool-call-body">${argsSection}${resultSection}</div>
        `;

        container.appendChild(card);

        // collaborate 工具调用：在卡片后渲染协同过程的多 Agent 对话历史
        if (toolName === 'collaborate' && record.collaborationDetail) {
            this.appendCollaborationTimeline(container, record.collaborationDetail);
        }
    }

    /**
     * 渲染协同过程的多 Agent 对话时间线。
     * 在 collaborate 工具卡片下方展示各 Agent 的逐轮发言，
     * 使用不同颜色区分不同角色，支持折叠/展开。
     *
     * @param {HTMLElement} container - 消息容器
     * @param {Object} detail - 协同详情 { mode, goal, participants, agentMessages, metrics, ... }
     */
    appendCollaborationTimeline(container, detail) {
        const timeline = document.createElement('div');
        timeline.className = 'collaboration-timeline';

        const agentMessages = detail.agentMessages || [];
        const participants = detail.participants || [];
        const mode = detail.mode || '';
        const totalRounds = detail.totalRounds || 0;

        // 为每个参与者分配颜色
        const roleColors = ['#6366f1', '#ec4899', '#f59e0b', '#10b981', '#8b5cf6', '#ef4444'];
        const roleColorMap = {};
        participants.forEach((name, index) => {
            roleColorMap[name] = roleColors[index % roleColors.length];
        });

        // 标题栏（可折叠）
        const headerHtml = `
            <div class="collab-timeline-header" onclick="this.parentElement.classList.toggle('collapsed')">
                <span class="collab-timeline-icon">🤝</span>
                <span class="collab-timeline-title">协同过程 · ${this.escapeHtml(mode)} · ${totalRounds} 轮</span>
                <span class="collab-timeline-participants">${participants.map(p =>
                    `<span class="collab-participant-tag" style="background:${roleColorMap[p] || '#6366f1'}20;color:${roleColorMap[p] || '#6366f1'}">${this.escapeHtml(p)}</span>`
                ).join('')}</span>
                <span class="collab-timeline-toggle">▼</span>
            </div>
        `;

        // 对话消息列表
        let messagesHtml = '<div class="collab-timeline-body">';
        for (const msg of agentMessages) {
            const role = msg.agentRole || msg.agentId || 'Unknown';
            const color = roleColorMap[role] || '#6366f1';
            const content = msg.content || '';
            // 使用 marked 渲染 Markdown（如果可用）
            const renderedContent = (typeof marked !== 'undefined')
                ? marked.parse(content)
                : this.escapeHtml(content).replace(/\n/g, '<br>');

            messagesHtml += `
                <div class="collab-message">
                    <div class="collab-message-role" style="color:${color}">
                        <span class="collab-role-dot" style="background:${color}"></span>
                        ${this.escapeHtml(role)}
                    </div>
                    <div class="collab-message-content">${renderedContent}</div>
                </div>
            `;
        }
        messagesHtml += '</div>';

        timeline.innerHTML = headerHtml + messagesHtml;
        container.appendChild(timeline);
    }

    /**
     * 加载左侧历史聊天会话列表，按天分组折叠显示
     */
    async loadChatSessions() {
        try {
            const response = await this.authFetch('/api/sessions');
            const sessions = await response.json();
            
            // 只显示 web: 开头的会话，按时间戳降序排列
            const webSessions = sessions
                .filter(s => s.key.startsWith('web:'))
                .sort((a, b) => {
                    const tsA = parseInt(a.key.substring(4)) || 0;
                    const tsB = parseInt(b.key.substring(4)) || 0;
                    return tsB - tsA;
                });
            
            const historyDiv = document.getElementById('chatHistory');
            if (webSessions.length === 0) {
                historyDiv.innerHTML = '<div class="chat-history-empty">No chat history</div>';
                return;
            }

            // 按天分组（key 格式 web:<timestamp>，取日期字符串作为分组 key）
            const todayLabel = this.formatDateLabel(new Date());
            const groups = new Map(); // dateLabel -> sessions[]
            for (const session of webSessions) {
                const timestamp = parseInt(session.key.substring(4)) || 0;
                const dateLabel = timestamp ? this.formatDateLabel(new Date(timestamp)) : 'Unknown';
                if (!groups.has(dateLabel)) groups.set(dateLabel, []);
                groups.get(dateLabel).push(session);
            }

            // 渲染分组 HTML（groups 已按时间倒排，Map 保持插入顺序）
            let html = '';
            for (const [dateLabel, groupSessions] of groups) {
                const isToday = dateLabel === todayLabel;
                const collapsedClass = isToday ? '' : 'collapsed';
                html += `
                    <div class="chat-history-group ${collapsedClass}" data-group-date="${this.escapeHtml(dateLabel)}">
                        <div class="chat-history-group-header" onclick="this.parentElement.classList.toggle('collapsed')">
                            <span class="group-date-label"><span class="group-icon">${dateLabel === 'Today' ? '⚡' : dateLabel === 'Yesterday' ? '🌙' : '🗓'}</span>${this.escapeHtml(dateLabel)}</span>
                            <span class="group-arrow">▾</span>
                        </div>
                        <div class="chat-history-group-items">
                            ${groupSessions.map(s => {
                                const isActive = s.key === this.chatSessionId;
                                const title = this.extractChatTitle(s.key, s.firstMessage);
                                return `
                                    <div class="chat-history-item ${isActive ? 'active' : ''}" data-session="${this.escapeHtml(s.key)}">
                                        <span class="history-title">${this.escapeHtml(title)}</span>
                                        <button class="history-delete" onclick="event.stopPropagation(); app.deleteChatSession('${this.escapeHtml(s.key)}')" title="Delete">×</button>
                                    </div>
                                `;
                            }).join('')}
                        </div>
                    </div>
                `;
            }
            // 恢复之前的折叠状态（避免切换 session 时分组被重置折叠）
            const previousCollapsedGroups = new Set(
                [...historyDiv.querySelectorAll('.chat-history-group.collapsed')]
                    .map(el => el.dataset.groupDate)
            );

            historyDiv.innerHTML = html;

            // 有历史状态时按历史状态恢复，否则保持渲染时的默认状态（今天展开，其他折叠）
            if (previousCollapsedGroups.size > 0 || historyDiv.querySelectorAll('.chat-history-group').length > 0) {
                historyDiv.querySelectorAll('.chat-history-group').forEach(groupEl => {
                    const groupDate = groupEl.dataset.groupDate;
                    if (previousCollapsedGroups.has(groupDate)) {
                        groupEl.classList.add('collapsed');
                    } else if (previousCollapsedGroups.size > 0) {
                        groupEl.classList.remove('collapsed');
                    }
                });
            }

            // 绑定点击事件
            historyDiv.querySelectorAll('.chat-history-item').forEach(item => {
                item.addEventListener('click', () => {
                    const sessionKey = item.dataset.session;
                    this.switchChatSession(sessionKey);
                });
            });
        } catch (error) {
            console.error('Failed to load chat sessions:', error);
        }
    }

    /**
     * 将 Date 格式化为日期分组标签，今天显示 "Today"，昨天显示 "Yesterday"，其余显示 yyyy/M/d
     */
    formatDateLabel(date) {
        const today = new Date();
        const todayStr = `${today.getFullYear()}/${today.getMonth() + 1}/${today.getDate()}`;
        const dateStr = `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`;
        if (dateStr === todayStr) return 'Today';
        const yesterday = new Date(today);
        yesterday.setDate(today.getDate() - 1);
        const yesterdayStr = `${yesterday.getFullYear()}/${yesterday.getMonth() + 1}/${yesterday.getDate()}`;
        if (dateStr === yesterdayStr) return 'Yesterday';
        return dateStr;
    }

    /**
     * 从会话 key 和 firstMessage 提取标题。
     * 优先级：localStorage 缓存的用户首条消息 > 后端返回的 firstMessage > 降级显示时间
     */
    extractChatTitle(key, firstMessage) {
        // 优先读 localStorage 中缓存的首条用户消息（服务重启后后端内存丢失时的兜底）
        const cachedTitle = localStorage.getItem(`tinyclaw_title_${key}`);
        if (cachedTitle && cachedTitle.trim()) {
            return cachedTitle.trim();
        }
        if (firstMessage && firstMessage.trim()) {
            return firstMessage.trim();
        }
        // 降级：从时间戳生成友好的时间字符串
        if (key.startsWith('web:')) {
            const timestamp = key.substring(4);
            if (/^\d+$/.test(timestamp)) {
                const date = new Date(parseInt(timestamp));
                return date.toLocaleString([], {
                    month: 'numeric', day: 'numeric',
                    hour: '2-digit', minute: '2-digit'
                });
            }
            return timestamp === 'default' ? 'Default Chat' : timestamp;
        }
        return key;
    }

    /**
     * 切换到指定聊天会话
     */
    switchChatSession(sessionKey) {
        this.chatSessionId = sessionKey;
        localStorage.setItem('tinyclaw_chat_session', this.chatSessionId);
        this.loadChatHistory();
        this.loadChatSessions();
    }

    /**
     * 删除聊天会话
     */
    async deleteChatSession(key) {
        if (!confirm('Delete this chat?')) return;
        try {
            await this.authFetch(`/api/sessions/${encodeURIComponent(key)}`, { method: 'DELETE' });
            // 清除该会话缓存的标题
            localStorage.removeItem(`tinyclaw_title_${key}`);
            // 如果删除的是当前会话，切换到新会话
            if (key === this.chatSessionId) {
                this.chatSessionId = 'web:default';
                localStorage.setItem('tinyclaw_chat_session', this.chatSessionId);
                document.getElementById('chatMessages').innerHTML = this.getWelcomeHtml();
                this.bindQuickPrompts();
            }
            this.loadChatSessions();
        } catch (error) {
            console.error('Failed to delete chat session:', error);
        }
    }

    async sendMessage() {
        const input = document.getElementById('chatInput');
        const sendBtn = document.getElementById('sendBtn');

        // 如果正在执行中，点击按钮触发中断（必须在空消息检查之前）
        if (this.currentAbortController) {
            this.abortCurrentTask();
            return;
        }

        const message = input.value.trim();
        const hasImages = this.pendingImages.length > 0;
        
        if (!message && !hasImages) return;

        input.value = '';
        input.style.height = 'auto';

        // 进入运行状态：按钮变为停止按钮
        this.currentAbortController = new AbortController();
        sendBtn.classList.add('loading');
        sendBtn.disabled = false;
        sendBtn.textContent = '■';
        sendBtn.title = '停止生成';

        const messagesDiv = document.getElementById('chatMessages');
        
        // Remove welcome message
        const welcome = messagesDiv.querySelector('.chat-welcome');
        if (welcome) welcome.remove();

        // 上传图片并获取文件路径
        let imagePaths = [];
        if (hasImages) {
            try {
                const uploadResp = await this.authFetch('/api/upload', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ images: this.pendingImages })
                });
                const uploadResult = await uploadResp.json();
                imagePaths = uploadResult.files || [];
            } catch (err) {
                console.error('Failed to upload images:', err);
            }
        }

        // 如果是该会话的第一条消息，缓存到 localStorage 作为会话标题
        // （后端内存会话重启后丢失，localStorage 可跨重启保持标题）
        const titleKey = `tinyclaw_title_${this.chatSessionId}`;
        if (message && !localStorage.getItem(titleKey)) {
            const titleText = message.length > 30 ? message.substring(0, 30) + '…' : message;
            localStorage.setItem(titleKey, titleText);
        }

        // Add user message (包含图片)
        this.addMessage(message, 'user', imagePaths);
        this.clearPendingImages();

        // Add assistant message placeholder for streaming
        const assistantDiv = document.createElement('div');
        assistantDiv.className = 'message assistant';
        assistantDiv.innerHTML = '<div class="message-content"></div>';
        messagesDiv.appendChild(assistantDiv);
        messagesDiv.scrollTop = messagesDiv.scrollHeight;

        const contentDiv = assistantDiv.querySelector('.message-content');

        // 渲染状态：跟踪当前正在流式输出的文本内容 div 和工具调用卡片
        let currentTextContent = '';
        let currentTextDiv = null;
        // toolCardMap: toolName -> { card, statusEl, bodyEl, resultEl, spinnerEl }
        const toolCardMap = {};
        // subagentCardMap: taskId -> { card, bodyEl, statusEl, contentBuffer }
        const subagentCardMap = {};

        /**
         * 获取或创建当前文本输出区域（用于流式追加 CONTENT 事件）。
         * 每次工具调用前后都会创建新的文本区域，保持内容与卡片的视觉分离。
         */
        const getOrCreateTextDiv = () => {
            if (!currentTextDiv) {
                currentTextDiv = document.createElement('div');
                currentTextDiv.className = 'message-content-text';
                contentDiv.appendChild(currentTextDiv);
            }
            return currentTextDiv;
        };

        /**
         * 将当前文本区域用 Markdown 渲染并封闭，下次 CONTENT 事件将新建文本区域。
         */
        const finalizeCurrentText = () => {
            if (currentTextDiv && currentTextContent) {
                // 流式阶段已经实时用 marked.parse 渲染，这里只需去掉流式光标，确保最终状态干净
                if (typeof marked !== 'undefined') {
                    currentTextDiv.classList.add('markdown-body');
                    currentTextDiv.style.whiteSpace = '';
                    currentTextDiv.innerHTML = marked.parse(currentTextContent);
                } else {
                    currentTextDiv.style.whiteSpace = 'pre-wrap';
                    currentTextDiv.textContent = currentTextContent;
                }
            }
            currentTextDiv = null;
            currentTextContent = '';
        };

        /**
         * 处理 TOOL_START 事件：创建工具调用卡片，显示工具名和运行状态。
         */
        const handleToolStart = (event) => {
            finalizeCurrentText();

            const toolName = event.tool || 'unknown';
            const args = event.args || {};

            const card = document.createElement('div');
            card.className = 'tool-call-card';

            // 将参数格式化为可读文本，还原 JSON 字符串中的转义换行符
            const argsText = Object.keys(args).length > 0
                ? JSON.stringify(args, null, 2).replace(/\\n/g, '\n').replace(/\\t/g, '\t')
                : '';

            const argsSection = argsText
                ? `<div class="tool-call-section"><div class="tool-call-section-label">参数</div><div class="tool-call-args">${this.escapeHtml(argsText)}</div></div>`
                : '';
            card.innerHTML = `<div class="tool-call-header" onclick="this.parentElement.classList.toggle('expanded')"><span class="tool-call-icon">🔧</span><span class="tool-call-name">${this.escapeHtml(toolName)}</span><span class="tool-call-status running"><span class="tool-call-spinner"></span>运行中</span><span class="tool-call-toggle">▼</span></div><div class="tool-call-body">${argsSection}<div class="tool-call-section tool-call-result-section" style="display:none"><div class="tool-call-section-label">结果</div><div class="tool-call-result"></div></div></div>`;

            contentDiv.appendChild(card);
            toolCardMap[toolName] = {
                card,
                statusEl: card.querySelector('.tool-call-status'),
                resultSectionEl: card.querySelector('.tool-call-result-section'),
                resultEl: card.querySelector('.tool-call-result'),
            };
        };

        /**
         * 处理 TOOL_END 事件：更新工具调用卡片状态，显示结果。
         */
        const handleToolEnd = (event) => {
            const toolName = event.tool || 'unknown';
            const success = event.success !== false;
            const result = event.result || '';
            const cardInfo = toolCardMap[toolName];

            if (cardInfo) {
                const { statusEl, resultSectionEl, resultEl } = cardInfo;

                // 更新状态图标
                statusEl.className = `tool-call-status ${success ? 'success' : 'error'}`;
                statusEl.innerHTML = success ? '✅ 完成' : '❌ 失败';

                // 显示结果（截断过长内容）
                const displayResult = result.length > 2000
                    ? result.substring(0, 2000) + '\n... (内容已截断)'
                    : result;
                resultEl.textContent = displayResult;
                if (!success) resultEl.classList.add('error-result');
                resultSectionEl.style.display = '';

                delete toolCardMap[toolName];
            }

            // 工具调用结束后，下一段文本需要新建文本区域
            currentTextDiv = null;
            currentTextContent = '';
        };

        /**
         * 处理 SUBAGENT_START 事件：创建子代理卡片。
         */
        const handleSubagentStart = (event) => {
            finalizeCurrentText();

            const taskId = event.taskId || 'unknown';
            const label = event.label || '';
            const task = event.task || '';
            const displayName = label || task.substring(0, 40) || '子代理';

            const card = document.createElement('div');
            card.className = 'subagent-card expanded';
            card.innerHTML = `<div class="subagent-header" onclick="this.parentElement.classList.toggle('expanded')"><span class="tool-call-icon">👤</span><span class="subagent-name">${this.escapeHtml(displayName)}</span><span class="subagent-status"><span class="tool-call-spinner"></span>执行中</span><span class="tool-call-toggle">▼</span></div><div class="subagent-body"></div>`;

            contentDiv.appendChild(card);
            subagentCardMap[taskId] = {
                card,
                bodyEl: card.querySelector('.subagent-body'),
                statusEl: card.querySelector('.subagent-status'),
                contentBuffer: '',
            };
        };

        /**
         * 处理 SUBAGENT_CONTENT 事件：将子代理输出追加到卡片内容区。
         */
        const handleSubagentContent = (event) => {
            const taskId = event.taskId || 'unknown';
            const content = event.content || '';
            const cardInfo = subagentCardMap[taskId];
            if (cardInfo) {
                cardInfo.contentBuffer += content;
                if (typeof marked !== 'undefined') {
                    cardInfo.bodyEl.classList.add('markdown-body');
                    cardInfo.bodyEl.style.whiteSpace = '';
                    cardInfo.bodyEl.innerHTML = marked.parse(cardInfo.contentBuffer) + '<span class="streaming-cursor"></span>';
                } else {
                    cardInfo.bodyEl.textContent = cardInfo.contentBuffer;
                }
            }
        };

        /**
         * 处理 SUBAGENT_END 事件：更新子代理卡片状态。
         */
        const handleSubagentEnd = (event) => {
            const taskId = event.taskId || 'unknown';
            const success = event.success !== false;
            const cardInfo = subagentCardMap[taskId];
            if (cardInfo) {
                cardInfo.statusEl.className = `subagent-status ${success ? 'success' : 'error'}`;
                cardInfo.statusEl.innerHTML = success ? '✅ 完成' : '❌ 失败';
                // 最终渲染 Markdown 并移除流式光标
                if (typeof marked !== 'undefined' && cardInfo.contentBuffer) {
                    cardInfo.bodyEl.classList.add('markdown-body');
                    cardInfo.bodyEl.style.whiteSpace = '';
                    cardInfo.bodyEl.innerHTML = marked.parse(cardInfo.contentBuffer);
                }
                cardInfo.bodyEl.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
                delete subagentCardMap[taskId];
            }
            currentTextDiv = null;
            currentTextContent = '';
        };

        /**
         * 处理单个 SSE JSON 事件，根据 type 分发到对应的渲染函数。
         */
        const handleSseEvent = (jsonStr) => {
            let event;
            try {
                event = JSON.parse(jsonStr);
            } catch {
                // 非 JSON 格式（旧版兼容）：作为普通文本追加
                const textDiv = getOrCreateTextDiv();
                currentTextContent += jsonStr;
                textDiv.style.whiteSpace = 'pre-wrap';
                textDiv.textContent = currentTextContent;
                const legacyCursor = document.createElement('span');
                legacyCursor.className = 'streaming-cursor';
                textDiv.appendChild(legacyCursor);
                return;
            }

            switch (event.type) {
                case 'CONTENT': {
                    const textDiv = getOrCreateTextDiv();
                    currentTextContent += event.content || '';
                    // 流式阶段实时用 marked.parse 渲染，保证列表等 Markdown 结构正确显示
                    if (typeof marked !== 'undefined') {
                        textDiv.classList.add('markdown-body');
                        textDiv.style.whiteSpace = '';
                        textDiv.innerHTML = marked.parse(currentTextContent) + '<span class="streaming-cursor"></span>';
                    } else {
                        textDiv.style.whiteSpace = 'pre-wrap';
                        textDiv.textContent = currentTextContent;
                        const cursor = document.createElement('span');
                        cursor.className = 'streaming-cursor';
                        textDiv.appendChild(cursor);
                    }
                    break;
                }
                case 'THINKING': {
                    const thinkingDiv = document.createElement('div');
                    thinkingDiv.className = 'thinking-block';
                    thinkingDiv.textContent = event.content || '';
                    contentDiv.appendChild(thinkingDiv);
                    break;
                }
                case 'TOOL_START':
                    handleToolStart(event);
                    break;
                case 'TOOL_END':
                    handleToolEnd(event);
                    break;
                case 'SUBAGENT_START':
                    handleSubagentStart(event);
                    break;
                case 'SUBAGENT_CONTENT':
                    handleSubagentContent(event);
                    break;
                case 'SUBAGENT_END':
                    handleSubagentEnd(event);
                    break;
                case 'COLLABORATE_START': {
                    finalizeCurrentText();
                    // 创建协同卡片（类似子代理卡片，默认展开）
                    const collabCard = document.createElement('div');
                    collabCard.className = 'subagent-card expanded';
                    collabCard.dataset.collabCard = 'true';
                    const collabTopic = event.topic || '';
                    const collabDisplayName = collabTopic.length > 40 ? collabTopic.substring(0, 40) + '…' : collabTopic;
                    collabCard.innerHTML = `<div class="subagent-header" onclick="this.parentElement.classList.toggle('expanded')"><span class="tool-call-icon">🤝</span><span class="subagent-name">${this.escapeHtml(collabDisplayName || '多 Agent 协同')}</span><span class="subagent-status"><span class="tool-call-spinner"></span>协同中</span><span class="tool-call-toggle">▼</span></div><div class="subagent-body" style="display:block"></div>`;
                    contentDiv.appendChild(collabCard);
                    // 将协同卡片的 body 作为后续 Agent 发言的容器
                    currentTextDiv = null;
                    currentTextContent = '';
                    // 保存协同卡片引用，供后续事件使用
                    this._currentCollabCard = collabCard;
                    this._currentCollabBody = collabCard.querySelector('.subagent-body');
                    break;
                }
                case 'COLLABORATE_AGENT': {
                    // 完整消息（非流式模式下使用）
                    finalizeCurrentText();
                    const collabBody = this._currentCollabBody || contentDiv;
                    const agentDiv = document.createElement('div');
                    agentDiv.className = 'collab-agent-message';
                    const agentName = event.agent || 'Agent';
                    const agentContent = event.content || '';
                    const renderedContent = (typeof marked !== 'undefined')
                        ? marked.parse(agentContent)
                        : this.escapeHtml(agentContent).replace(/\n/g, '<br>');
                    agentDiv.innerHTML = `<div class="collab-agent-name">💬 ${this.escapeHtml(agentName)}</div><div class="collab-agent-content markdown-body">${renderedContent}</div>`;
                    collabBody.appendChild(agentDiv);
                    currentTextDiv = null;
                    currentTextContent = '';
                    break;
                }
                case 'COLLABORATE_AGENT_CHUNK': {
                    // 流式增量：逐 chunk 追加到当前 Agent 的发言区域
                    const chunkAgent = event.agent || 'Agent';
                    const chunkContent = event.content || '';
                    const chunkCollabBody = this._currentCollabBody || contentDiv;
                    if (!currentTextDiv || currentTextDiv.dataset.collabAgent !== chunkAgent) {
                        // 新 Agent 开始发言，创建新的发言区域
                        finalizeCurrentText();
                        const agentBlock = document.createElement('div');
                        agentBlock.className = 'collab-agent-message';
                        agentBlock.innerHTML = `<div class="collab-agent-name">💬 ${this.escapeHtml(chunkAgent)}</div><div class="collab-agent-content"></div>`;
                        chunkCollabBody.appendChild(agentBlock);
                        currentTextDiv = agentBlock.querySelector('.collab-agent-content');
                        currentTextDiv.dataset.collabAgent = chunkAgent;
                        currentTextContent = '';
                    }
                    currentTextContent += chunkContent;
                    if (typeof marked !== 'undefined') {
                        currentTextDiv.classList.add('markdown-body');
                        currentTextDiv.innerHTML = marked.parse(currentTextContent) + '<span class="streaming-cursor"></span>';
                    } else {
                        currentTextDiv.innerHTML = this.escapeHtml(currentTextContent).replace(/\n/g, '<br>') + '<span class="streaming-cursor"></span>';
                    }
                    break;
                }
                case 'COLLABORATE_END': {
                    finalizeCurrentText();
                    // 更新协同卡片状态
                    if (this._currentCollabCard) {
                        const statusEl = this._currentCollabCard.querySelector('.subagent-status');
                        if (statusEl) {
                            statusEl.className = 'subagent-status success';
                            statusEl.innerHTML = '✅ 完成';
                        }
                        // 移除流式光标
                        this._currentCollabCard.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
                        this._currentCollabCard = null;
                        this._currentCollabBody = null;
                    }
                    currentTextDiv = null;
                    break;
                }
                default: {
                    // 未知事件类型：尝试作为文本内容处理
                    const fallbackContent = event.content || event.result || '';
                    if (fallbackContent) {
                        const textDiv = getOrCreateTextDiv();
                        currentTextContent += fallbackContent;
                        textDiv.innerHTML = this.escapeHtml(currentTextContent).replace(/\n/g, '<br>') + '<span class="streaming-cursor"></span>';
                    }
                }
            }
        };

        try {
            // 使用流式 API，包含图片路径
            const response = await this.authFetch('/api/chat/stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ 
                    message, 
                    sessionId: this.chatSessionId,
                    images: imagePaths.length > 0 ? imagePaths : undefined
                }),
                signal: this.currentAbortController?.signal
            });

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            // 用于跨 chunk 拼接不完整行（网络缓冲区可能在行中间切断）
            let lineBuffer = '';
            let streamDone = false;

            while (!streamDone) {
                const { done, value } = await reader.read();
                if (done) break;

                // 将新数据追加到行缓冲区
                lineBuffer += decoder.decode(value, { stream: true });

                // 按换行符切分，最后一段可能不完整，留在 buffer 里
                const lines = lineBuffer.split('\n');
                lineBuffer = lines.pop(); // 最后一段（可能不完整）留存

                for (const line of lines) {
                    if (!line.startsWith('data: ')) continue;

                    const data = line.slice(6);
                    if (data === '[DONE]') {
                        streamDone = true;
                        break;
                    } else if (data.startsWith('[ERROR]')) {
                        const errorText = data.slice(7);
                        const textDiv = getOrCreateTextDiv();
                        currentTextContent += errorText;
                        textDiv.innerHTML = this.escapeHtml(currentTextContent).replace(/\n/g, '<br>');
                    } else {
                        // 每个 data: 行是一个完整的单行 JSON 事件，直接解析
                        handleSseEvent(data);
                    }
                    messagesDiv.scrollTop = messagesDiv.scrollHeight;
                }
            }

            // 流结束：将最后一段文本用 Markdown 渲染
            finalizeCurrentText();
            // 移除所有残留的流式光标
            contentDiv.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
            
            // 刷新左侧会话列表
            this.loadChatSessions();
        } catch (error) {
            if (error.name === 'AbortError') {
                // 用户主动中断，不显示错误
                finalizeCurrentText();
            } else {
                const textDiv = getOrCreateTextDiv();
                textDiv.textContent = 'Error: ' + error.message;
            }
        } finally {
            // 恢复按钮状态：可点击，恢复圆形
            this.currentAbortController = null;
            sendBtn.classList.remove('loading');
            sendBtn.disabled = false;
            sendBtn.textContent = '↑';
            sendBtn.title = '';
        }
    }

    /**
     * 中断当前正在执行的 LLM 任务。
     * 同时发送 abort 请求到后端，并取消前端的 fetch 请求。
     */
    async abortCurrentTask() {
        if (this.currentAbortController) {
            // 先通知后端中断
            try {
                await this.authFetch('/api/chat/abort', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' }
                });
            } catch (e) {
                console.warn('Failed to send abort to server:', e);
            }
            // 再取消前端 fetch
            this.currentAbortController.abort();
        }
    }

    addMessage(content, role, images = [], scroll = true) {
        const messagesDiv = document.getElementById('chatMessages');
        const div = document.createElement('div');
        div.className = `message ${role}`;
        
        let html = '';
        
        // 显示图片（如果有）
        if (images && images.length > 0) {
            html += '<div class="message-images">';
            for (const imgPath of images) {
                // 图片路径可能是相对路径或 Base64
                const imgSrc = imgPath.startsWith('data:') ? imgPath : `/api/files/${imgPath}`;
                html += `<img src="${imgSrc}" alt="Image" class="message-image" onclick="window.open('${imgSrc}', '_blank')">`;
            }
            html += '</div>';
        }
        
        // assistant 消息使用 Markdown 渲染，user 消息纯文本
        if (role === 'assistant' && typeof marked !== 'undefined') {
            html += `<div class="message-content markdown-body">${marked.parse(content || '')}</div>`;
        } else {
            html += `<div class="message-content">${this.escapeHtml(content || '')}</div>`;
        }
        
        div.innerHTML = html;
        messagesDiv.appendChild(div);
        if (scroll) {
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }
    }

    // ==================== Channels ====================

    async loadChannels() {
        try {
            const response = await this.authFetch('/api/channels');
            const channels = await response.json();
            
            const grid = document.getElementById('channelsGrid');
            grid.innerHTML = channels.map(ch => `
                <div class="card" data-channel="${ch.name}">
                    <div class="card-header">
                        <span class="badge ${ch.enabled ? 'badge-success' : 'badge-disabled'}">
                            ${ch.enabled ? 'Enabled' : 'Disabled'}
                        </span>
                        <span class="card-title">${this.capitalize(ch.name)}</span>
                    </div>
                    <div class="card-body">
                        <p>Bot Prefix: Not set</p>
                        <p>Click card to edit</p>
                    </div>
                    <div class="card-footer">
                        <button class="btn btn-text" onclick="app.editChannel('${ch.name}')">⚙️ Settings</button>
                    </div>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load channels:', error);
        }
    }

    async editChannel(name) {
        try {
            const response = await this.authFetch(`/api/channels/${name}`);
            const channel = await response.json();

            this.showModal(`Edit ${this.capitalize(name)}`, `
                <div class="form-group">
                    <label>Enabled</label>
                    <select class="form-control" id="modalEnabled">
                        <option value="true" ${channel.enabled ? 'selected' : ''}>Yes</option>
                        <option value="false" ${!channel.enabled ? 'selected' : ''}>No</option>
                    </select>
                </div>
                ${this.getChannelFields(name, channel)}
            `, async () => {
                const data = { enabled: document.getElementById('modalEnabled').value === 'true' };
                this.collectChannelData(name, data);
                
                await this.authFetch(`/api/channels/${name}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                this.loadChannels();
            });
        } catch (error) {
            console.error('Failed to load channel:', error);
        }
    }

    getChannelFields(name, ch) {
        switch (name) {
            case 'telegram':
            case 'discord':
                return `<div class="form-group"><label>Token</label><input class="form-control" id="modalToken" value="${ch.token || ''}"></div>`;
            case 'feishu':
                return `
                    <div class="form-group"><label>App ID</label><input class="form-control" id="modalAppId" value="${ch.appId || ''}"></div>
                    <div class="form-group"><label>App Secret</label><input class="form-control" id="modalAppSecret" value="${ch.appSecret || ''}"></div>
                `;
            case 'dingtalk':
                return `
                    <div class="form-group"><label>Client ID</label><input class="form-control" id="modalClientId" value="${ch.clientId || ''}"></div>
                    <div class="form-group"><label>Client Secret</label><input class="form-control" id="modalClientSecret" value="${ch.clientSecret || ''}"></div>
                `;
            case 'qq':
                return `
                    <div class="form-group"><label>App ID</label><input class="form-control" id="modalAppId" value="${ch.appId || ''}"></div>
                    <div class="form-group"><label>App Secret</label><input class="form-control" id="modalAppSecret" value="${ch.appSecret || ''}"></div>
                `;
            default:
                return '';
        }
    }

    collectChannelData(name, data) {
        switch (name) {
            case 'telegram':
            case 'discord':
                data.token = document.getElementById('modalToken').value;
                break;
            case 'feishu':
                data.appId = document.getElementById('modalAppId').value;
                data.appSecret = document.getElementById('modalAppSecret').value;
                break;
            case 'dingtalk':
                data.clientId = document.getElementById('modalClientId').value;
                data.clientSecret = document.getElementById('modalClientSecret').value;
                break;
            case 'qq':
                data.appId = document.getElementById('modalAppId').value;
                data.appSecret = document.getElementById('modalAppSecret').value;
                break;
        }
    }

    // ==================== Sessions ====================

    async loadSessions() {
        try {
            const response = await this.authFetch('/api/sessions');
            const sessions = await response.json();
            
            this.allSessions = sessions.map((s, index) => ({
                id: this.generateSessionId(s.key),
                name: this.extractSessionName(s.key),
                sessionId: s.key,
                userId: this.extractUserId(s.key),
                messageCount: s.messageCount
            }));
            
            // 初始化过滤器
            this.initSessionFilters();
            
            // 渲染表格
            this.renderSessionsTable();
            
            // 绑定事件
            this.bindSessionEvents();
        } catch (error) {
            console.error('Failed to load sessions:', error);
        }
    }
    
    generateSessionId(key) {
        // 从 key 中提取前 8 位作为简短 ID
        return key.replace(/[^a-zA-Z0-9]/g, '').substring(0, 24);
    }
    
    extractSessionName(key) {
        // 如果包含冒号，尝试提取可读的部分
        if (key.includes(':')) {
            const parts = key.split(':');
            return parts[parts.length - 1] || key;
        }
        return key;
    }
    
    extractUserId(key) {
        // 从 sessionId 中提取 userId（通常是 channel:userId 格式）
        if (key.includes(':')) {
            const parts = key.split(':');
            return parts.length > 1 ? parts[1] : parts[0];
        }
        return 'default';
    }
    
    initSessionFilters() {
        // 提取唯一的 channel 列表
        const channels = [...new Set(this.allSessions.map(s => s.sessionId.split(':')[0]))].sort();
        const channelSelect = document.getElementById('filterChannel');
        channelSelect.innerHTML = '<option value="">Filter by Channel</option>' +
            channels.map(c => `<option value="${c}">${this.capitalize(c)}</option>`).join('');
    }
    
    renderSessionsTable() {
        const tbody = document.getElementById('sessionsTableBody');
        
        // 应用过滤
        const userIdFilter = document.getElementById('filterUserId')?.value.toLowerCase() || '';
        const channelFilter = document.getElementById('filterChannel')?.value || '';
        
        let filteredSessions = this.allSessions;
        if (userIdFilter) {
            filteredSessions = filteredSessions.filter(s => 
                s.userId.toLowerCase().includes(userIdFilter)
            );
        }
        if (channelFilter) {
            filteredSessions = filteredSessions.filter(s => 
                s.sessionId.startsWith(channelFilter + ':')
            );
        }
        
        // 分页
        const pageSize = 10;
        const currentPage = this.currentSessionPage || 1;
        const totalPages = Math.ceil(filteredSessions.length / pageSize);
        const start = (currentPage - 1) * pageSize;
        const end = start + pageSize;
        const pageSessions = filteredSessions.slice(start, end);
        
        // 更新分页信息
        document.getElementById('totalSessions').textContent = filteredSessions.length;
        document.getElementById('currentPage').textContent = totalPages > 0 ? currentPage : 0;
        document.getElementById('paginationInfo').textContent = `${totalPages > 0 ? currentPage : 0} / ${totalPages}`;
        
        // 更新翻页按钮状态
        document.getElementById('prevPage').disabled = currentPage <= 1;
        document.getElementById('nextPage').disabled = currentPage >= totalPages;
        
        if (pageSessions.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty-state">No sessions found</td></tr>';
            return;
        }
        
        tbody.innerHTML = pageSessions.map(s => `
            <tr data-session-key="${this.escapeHtml(s.sessionId)}">
                <td class="col-checkbox">
                    <input type="checkbox" class="session-checkbox" value="${this.escapeHtml(s.sessionId)}">
                </td>
                <td class="col-id">${this.escapeHtml(s.id)}</td>
                <td class="col-name">${this.escapeHtml(s.name)}</td>
                <td class="col-session-id">${this.escapeHtml(s.sessionId)}</td>
                <td class="col-user-id">${this.escapeHtml(s.userId)}</td>
                <td class="col-action">
                    <div class="action-buttons">
                        <button class="btn-edit" onclick="app.viewSessionDetail('${this.escapeHtml(s.sessionId)}')">Edit</button>
                        <button class="btn-delete" onclick="app.deleteSession('${this.escapeHtml(s.sessionId)}')">Delete</button>
                    </div>
                </td>
            </tr>
        `).join('');
    }
    
    bindSessionEvents() {
        // 过滤事件
        document.getElementById('filterUserId').addEventListener('input', () => {
            this.currentSessionPage = 1;
            this.renderSessionsTable();
        });
        
        document.getElementById('filterChannel').addEventListener('change', () => {
            this.currentSessionPage = 1;
            this.renderSessionsTable();
        });
        
        // 全选
        document.getElementById('selectAllSessions').addEventListener('change', (e) => {
            document.querySelectorAll('.session-checkbox').forEach(cb => {
                cb.checked = e.target.checked;
            });
        });
        
        // 分页（使用 onclick 赋值避免重复绑定导致页码跳跃）
        document.getElementById('prevPage').onclick = () => {
            if (this.currentSessionPage > 1) {
                this.currentSessionPage--;
                this.renderSessionsTable();
            }
        };
        
        document.getElementById('nextPage').onclick = () => {
            const pageSize = 10;
            const totalPages = Math.ceil(this.allSessions.length / pageSize);
            if (this.currentSessionPage < totalPages) {
                this.currentSessionPage++;
                this.renderSessionsTable();
            }
        };
    }
    
    async viewSessionDetail(key) {
        try {
            const response = await this.authFetch(`/api/sessions/${encodeURIComponent(key)}`);
            const messages = await response.json();
            
            let content = `<div style="max-height: 400px; overflow-y: auto;">`;
            if (messages.length === 0) {
                content += '<p class="empty-state">No messages in this session</p>';
            } else {
                content += messages.map(m => `
                    <div class="message ${m.role}" style="margin-bottom: 16px;">
                        <div style="font-weight: 600; margin-bottom: 4px; color: var(--text-secondary);">${this.capitalize(m.role)}</div>
                        <div style="background: var(--bg); padding: 12px; border-radius: 8px;">${this.escapeHtml(m.content)}</div>
                    </div>
                `).join('');
            }
            content += '</div>';
            
            this.showModal(`Session: ${key}`, content, null);
            document.getElementById('modalConfirm').style.display = 'none';
        } catch (error) {
            console.error('Failed to load session:', error);
        }
    }

    async deleteSession(key) {
        if (!confirm('Delete this session?')) return;
        try {
            await this.authFetch(`/api/sessions/${encodeURIComponent(key)}`, { method: 'DELETE' });
            this.loadSessions();
        } catch (error) {
            console.error('Failed to delete session:', error);
        }
    }

    // ==================== Cron Jobs ====================

    async loadCronJobs() {
        document.getElementById('addCronBtn').onclick = () => this.showAddCronModal();
        try {
            const response = await this.authFetch('/api/cron');
            const jobs = await response.json();
            
            const list = document.getElementById('cronList');
            if (jobs.length === 0) {
                list.innerHTML = '<p class="empty-state">No cron jobs configured</p>';
                return;
            }
            
            list.innerHTML = jobs.map(job => `
                <div class="cron-item">
                    <div class="cron-info">
                        <div class="cron-name">${job.name}</div>
                        <div class="cron-meta">${job.schedule} • ${job.message.substring(0, 50)}...</div>
                    </div>
                    <span class="badge ${job.enabled ? 'badge-success' : 'badge-disabled'}">${job.enabled ? 'Enabled' : 'Disabled'}</span>
                    <div class="cron-actions">
                        <button class="btn btn-secondary btn-sm" onclick="app.toggleCronJob('${job.id}', ${!job.enabled})">${job.enabled ? 'Disable' : 'Enable'}</button>
                        <button class="btn btn-secondary btn-sm" onclick="app.deleteCronJob('${job.id}')">Delete</button>
                    </div>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load cron jobs:', error);
        }

    }

    showAddCronModal() {
        this.showModal('Add Cron Job', `
            <div class="form-group">
                <label>Name</label>
                <input class="form-control" id="cronName" placeholder="Job name">
            </div>
            <div class="form-group">
                <label>Message</label>
                <textarea class="form-control" id="cronMessage" rows="3" placeholder="Task message for agent"></textarea>
            </div>
            <div class="form-group">
                <label>Schedule Type</label>
                <select class="form-control" id="cronType">
                    <option value="every">Every X seconds</option>
                    <option value="cron">Cron expression</option>
                </select>
            </div>
            <div class="form-group" id="cronEveryGroup">
                <label>Interval (seconds)</label>
                <input class="form-control" id="cronEvery" type="number" value="3600">
            </div>
            <div class="form-group" id="cronExprGroup" style="display:none;">
                <label>Cron Expression</label>
                <input class="form-control" id="cronExpr" placeholder="0 8 * * *">
            </div>
            <div class="form-group">
                <label>Channel (optional)</label>
                <input class="form-control" id="cronChannel" placeholder="e.g. dingtalk, telegram (leave empty for default)">
            </div>
            <div class="form-group">
                <label>To / Chat ID (optional)</label>
                <input class="form-control" id="cronTo" placeholder="Target chat ID (leave empty to use channel default)">
            </div>

        `, async () => {
            const data = {
                name: document.getElementById('cronName').value,
                message: document.getElementById('cronMessage').value
            };
            if (document.getElementById('cronType').value === 'every') {
                data.everySeconds = parseInt(document.getElementById('cronEvery').value);
            } else {
                data.cron = document.getElementById('cronExpr').value;
            }
            const channel = document.getElementById('cronChannel').value.trim();
            const to = document.getElementById('cronTo').value.trim();
            if (channel) data.channel = channel;
            if (to) data.to = to;
            await this.authFetch('/api/cron', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            this.loadCronJobs();
        });

        // 切换调度类型显示
        document.getElementById('cronType').addEventListener('change', (e) => {
            document.getElementById('cronEveryGroup').style.display = e.target.value === 'every' ? '' : 'none';
            document.getElementById('cronExprGroup').style.display = e.target.value === 'cron' ? '' : 'none';
        });

        document.getElementById('cronType').onchange = (e) => {
            document.getElementById('cronEveryGroup').style.display = e.target.value === 'every' ? 'block' : 'none';
            document.getElementById('cronExprGroup').style.display = e.target.value === 'cron' ? 'block' : 'none';
        };
    }

    async toggleCronJob(id, enabled) {
        await this.authFetch(`/api/cron/${id}/enable`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled })
        });
        this.loadCronJobs();
    }

    async deleteCronJob(id) {
        if (!confirm('Delete this job?')) return;
        await this.authFetch(`/api/cron/${id}`, { method: 'DELETE' });
        this.loadCronJobs();
    }

    // ==================== Workspace ====================

    async loadWorkspaceFiles() {
        try {
            // 获取 workspace 路径
            const configResponse = await this.authFetch('/api/config/agent');
            const config = await configResponse.json();
            // workspace 路径可能在配置中，这里暂时显示默认路径
            
            const response = await this.authFetch('/api/workspace/files');
            const files = await response.json();
            
            const list = document.getElementById('workspaceFiles');
            if (files.length === 0) {
                list.innerHTML = '<div class="empty-state">No files found</div>';
                return;
            }
            
            list.innerHTML = files.map(f => {
                const sizeText = f.size ? this.formatFileSize(f.size) : '-';
                const timeText = f.lastModified ? this.formatTimeAgo(f.lastModified) : '-';
                
                return `
                    <div class="file-card" data-file="${f.name}" onclick="app.loadFile('${f.name}')">
                        <div class="file-card-info">
                            <div class="file-card-name">${f.name}</div>
                            <div class="file-card-meta">${sizeText} · ${timeText}</div>
                        </div>
                        <div class="file-card-arrow">▶</div>
                    </div>
                `;
            }).join('');
        } catch (error) {
            console.error('Failed to load workspace files:', error);
        }

        // 绑定事件
        document.getElementById('saveFileBtn').onclick = () => this.saveCurrentFile();
        document.getElementById('refreshFilesBtn').onclick = () => this.loadWorkspaceFiles();
    }
    
    formatFileSize(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }
    
    formatTimeAgo(timestamp) {
        const now = Date.now();
        const diff = now - timestamp;
        const minutes = Math.floor(diff / 60000);
        const hours = Math.floor(diff / 3600000);
        const days = Math.floor(diff / 86400000);
        
        if (days > 0) return days + 'd ago';
        if (hours > 0) return hours + 'h ago';
        if (minutes > 0) return minutes + 'm ago';
        return 'just now';
    }

    async loadFile(name) {
        document.querySelectorAll('.file-card').forEach(item => {
            item.classList.toggle('active', item.dataset.file === name);
        });

        try {
            const response = await this.authFetch(`/api/workspace/files/${encodeURIComponent(name)}`);
            const data = await response.json();
            
            // 显示编辑器，隐藏占位符
            document.getElementById('editorPlaceholder').style.display = 'none';
            document.getElementById('editorContainer').style.display = 'flex';
            
            document.getElementById('editorFileName').textContent = name;
            document.getElementById('editorContent').value = data.content;
            this.currentEditingFile = name;
        } catch (error) {
            console.error('Failed to load file:', error);
        }
    }

    async saveCurrentFile() {
        if (!this.currentEditingFile) return;
        
        const content = document.getElementById('editorContent').value;
        try {
            await this.authFetch(`/api/workspace/files/${encodeURIComponent(this.currentEditingFile)}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content })
            });
            
            // 临时改变按钮文本
            const btn = document.getElementById('saveFileBtn');
            const originalText = btn.textContent;
            btn.textContent = 'Saved!';
            setTimeout(() => {
                btn.textContent = originalText;
            }, 1500);
            
            // 刷新文件列表以更新修改时间
            this.loadWorkspaceFiles();
        } catch (error) {
            alert('Failed to save: ' + error.message);
        }
    }
    
    showUploadModal() {
        this.showModal('Upload File', `
            <div class="form-group">
                <label>File Name</label>
                <input class="form-control" id="uploadFileName" placeholder="e.g., CUSTOM.md">
            </div>
            <div class="form-group">
                <label>Content</label>
                <textarea class="form-control" id="uploadFileContent" rows="10" placeholder="File content..."></textarea>
            </div>
        `, async () => {
            const name = document.getElementById('uploadFileName').value.trim();
            const content = document.getElementById('uploadFileContent').value;
            
            if (!name) {
                alert('Please enter a file name');
                return;
            }
            
            try {
                await this.authFetch(`/api/workspace/files/${encodeURIComponent(name)}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ content })
                });
                this.loadWorkspaceFiles();
            } catch (error) {
                alert('Upload failed: ' + error.message);
            }
        });
    }
    
    async downloadCurrentFile() {
        if (!this.currentEditingFile) {
            alert('Please select a file first');
            return;
        }
        
        const content = document.getElementById('editorContent').value;
        const blob = new Blob([content], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = this.currentEditingFile;
        a.click();
        URL.revokeObjectURL(url);
    }

    // ==================== Skills ====================

    async loadSkills() {
        try {
            const response = await this.authFetch('/api/skills');
            const skills = await response.json();
            
            const grid = document.getElementById('skillsGrid');
            if (skills.length === 0) {
                grid.innerHTML = '<p class="empty-state">No skills installed</p>';
                return;
            }
            
            grid.innerHTML = skills.map(s => `
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">${s.name}</span>
                        <span class="badge badge-outline">${s.source}</span>
                    </div>
                    <div class="card-body">
                        <p>${s.description || 'No description'}</p>
                    </div>
                    <div class="card-footer">
                        <button class="btn btn-text" onclick="app.viewSkill('${s.name}')">View</button>
                        ${s.source === 'workspace' ? `
                        <button class="btn btn-text" onclick="app.editSkill('${s.name}')">Edit</button>
                        <button class="btn btn-text btn-danger" onclick="app.deleteSkill('${s.name}')">Delete</button>
                        ` : ''}
                    </div>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load skills:', error);
        }
    }

    async viewSkill(name) {
        try {
            const response = await this.authFetch(`/api/skills/${encodeURIComponent(name)}`);
            const skill = await response.json();
            
            this.showModal(`Skill: ${name}`, `
                <pre style="white-space: pre-wrap; font-size: 13px; background: var(--bg); padding: 16px; border-radius: 8px; max-height: 400px; overflow: auto;">${this.escapeHtml(skill.content)}</pre>
            `, null);
            document.getElementById('modalConfirm').style.display = 'none';
        } catch (error) {
            console.error('Failed to load skill:', error);
        }
    }

    async editSkill(name) {
        try {
            const response = await this.authFetch(`/api/skills/${encodeURIComponent(name)}`);
            const skill = await response.json();

            this.showModal(`Edit Skill: ${name}`, `
                <textarea id="editSkillContent" style="width:100%; height:400px; font-family:monospace; font-size:13px; padding:12px; border:1px solid var(--border); border-radius:8px; background:var(--bg); resize:vertical;">${this.escapeHtml(skill.content)}</textarea>
            `, async () => {
                const content = document.getElementById('editSkillContent').value;
                const saveResp = await this.authFetch(`/api/skills/${encodeURIComponent(name)}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ content })
                });
                if (saveResp.ok) {
                    await this.loadSkills();
                } else {
                    const err = await saveResp.json();
                    alert('Failed to save skill: ' + (err.error || saveResp.status));
                }
            });
            document.getElementById('modalConfirm').textContent = 'Save';
            document.getElementById('modalConfirm').style.display = 'block';
        } catch (error) {
            console.error('Failed to edit skill:', error);
        }
    }

    async deleteSkill(name) {
        if (!confirm(`Delete workspace skill "${name}"? This cannot be undone.`)) return;
        try {
            const response = await this.authFetch(`/api/skills/${encodeURIComponent(name)}`, {
                method: 'DELETE'
            });
            if (response.ok) {
                await this.loadSkills();
            } else {
                const err = await response.json();
                alert('Failed to delete skill: ' + (err.error || response.status));
            }
        } catch (error) {
            console.error('Failed to delete skill:', error);
        }
    }

    // ==================== MCP Servers ====================

    async loadMcpServers() {
        try {
            const response = await this.authFetch('/api/mcp');
            const data = await response.json();

            // 设置全局开关状态
            const toggle = document.getElementById('mcpEnabledToggle');
            toggle.checked = data.enabled;
            toggle.onchange = () => this.toggleMcpEnabled(toggle.checked);

            // 绑定添加按钮
            document.getElementById('addMcpServerBtn').onclick = () => this.showAddMcpServerModal();

            const grid = document.getElementById('mcpServersGrid');
            const servers = data.servers || [];

            if (servers.length === 0) {
                grid.innerHTML = '<p class="empty-state">No MCP servers configured</p>';
                return;
            }

            grid.innerHTML = servers.map(s => {
                const statusBadge = s.enabled
                    ? '<span class="badge badge-success">Enabled</span>'
                    : '<span class="badge badge-disabled">Disabled</span>';
                const serverType = (s.type || 'sse').toUpperCase();
                const isStdio = (s.type || 'sse') === 'stdio';

                let connectionInfo = '';
                if (isStdio) {
                    const cmdDisplay = s.command || 'Not set';
                    const argsDisplay = s.args && s.args.length > 0 ? s.args.join(' ') : '';
                    connectionInfo = `
                        <div class="provider-field">
                            <span class="provider-field-label">Command:</span>
                            <span>${this.escapeHtml(cmdDisplay + (argsDisplay ? ' ' + argsDisplay : ''))}</span>
                        </div>`;
                } else {
                    const endpointDisplay = s.endpoint
                        ? `<span title="${this.escapeHtml(s.endpoint)}">${this.truncateUrl(s.endpoint)}</span>`
                        : '<span class="not-set">Not set</span>';
                    const apiKeyDisplay = s.apiKey
                        ? `<span class="masked">${this.escapeHtml(s.apiKey)}</span>`
                        : '<span class="not-set">Not set</span>';
                    connectionInfo = `
                        <div class="provider-field">
                            <span class="provider-field-label">Endpoint:</span>
                            ${endpointDisplay}
                        </div>
                        <div class="provider-field">
                            <span class="provider-field-label">API Key:</span>
                            ${apiKeyDisplay}
                        </div>`;
                }

                return `
                    <div class="card">
                        <div class="card-header">
                            <span class="card-title">${this.escapeHtml(s.name)}</span>
                            <span class="badge">${serverType}</span>
                            ${statusBadge}
                        </div>
                        <div class="card-body">
                            <p>${this.escapeHtml(s.description) || 'No description'}</p>
                            ${connectionInfo}
                            <div class="provider-field">
                                <span class="provider-field-label">Timeout:</span>
                                <span>${s.timeout}ms</span>
                            </div>
                        </div>
                        <div id="mcpTools-${this.escapeHtml(s.name)}" class="mcp-tools-section" style="display:none"></div>
                        <div class="card-footer">
                            <button class="btn btn-text" onclick="app.testMcpServer('${this.escapeHtml(s.name)}')">🔌 Test</button>
                            <button class="btn btn-text" onclick="app.showEditMcpServerModal('${this.escapeHtml(s.name)}')">Edit</button>
                            <button class="btn btn-text btn-danger" onclick="app.deleteMcpServer('${this.escapeHtml(s.name)}')">Delete</button>
                        </div>
                    </div>
                `;
            }).join('');
        } catch (error) {
            console.error('Failed to load MCP servers:', error);
        }
    }

    async toggleMcpEnabled(enabled) {
        try {
            await this.authFetch('/api/mcp', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            });
        } catch (error) {
            console.error('Failed to toggle MCP:', error);
            // 回滚 toggle 状态
            document.getElementById('mcpEnabledToggle').checked = !enabled;
        }
    }

    showAddMcpServerModal() {
        this.showModal('Add MCP Server', `
            <div class="form-group">
                <label>Name <span style="color:red">*</span></label>
                <input class="form-control" id="mcpServerName" placeholder="e.g., my-mcp-server">
            </div>
            <div class="form-group">
                <label>Description</label>
                <input class="form-control" id="mcpServerDesc" placeholder="Server description">
            </div>
            <div class="form-group">
                <label>Transport Type</label>
                <select class="form-control" id="mcpServerType" onchange="app.toggleMcpTypeFields()">
                    <option value="sse">SSE (HTTP)</option>
                    <option value="streamable-http">Streamable HTTP</option>
                    <option value="stdio">Stdio (Local Process)</option>
                </select>
            </div>
            <div id="mcpSseFields">
                <div class="form-group">
                    <label>Endpoint <span style="color:red">*</span></label>
                    <input class="form-control" id="mcpServerEndpoint" placeholder="https://example.com/mcp/sse">
                </div>
                <div class="form-group">
                    <label>API Key</label>
                    <input class="form-control" id="mcpServerApiKey" placeholder="Optional API key">
                </div>
            </div>
            <div id="mcpStdioFields" style="display:none">
                <div class="form-group">
                    <label>Command <span style="color:red">*</span></label>
                    <input class="form-control" id="mcpServerCommand" placeholder="e.g., npx, python3, node">
                </div>
                <div class="form-group">
                    <label>Arguments (one per line)</label>
                    <textarea class="form-control" id="mcpServerArgs" rows="3" placeholder="e.g.,\n-y\n@modelcontextprotocol/server-filesystem\n/path/to/dir"></textarea>
                </div>
            </div>
            <div class="form-row">
                <div class="form-group">
                    <label>Timeout (ms)</label>
                    <input type="number" class="form-control" id="mcpServerTimeout" value="30000">
                </div>
                <div class="form-group">
                    <label>Enabled</label>
                    <select class="form-control" id="mcpServerEnabled">
                        <option value="true">Yes</option>
                        <option value="false">No</option>
                    </select>
                </div>
            </div>
        `, async () => {
            const name = document.getElementById('mcpServerName').value.trim();
            const type = document.getElementById('mcpServerType').value;
            const isStdio = type === 'stdio';

            if (!name) { alert('Server name is required'); return; }

            const payload = {
                name,
                type,
                description: document.getElementById('mcpServerDesc').value.trim(),
                timeout: parseInt(document.getElementById('mcpServerTimeout').value) || 30000,
                enabled: document.getElementById('mcpServerEnabled').value === 'true'
            };

            if (type === 'stdio') {
                const command = document.getElementById('mcpServerCommand').value.trim();
                if (!command) { alert('Command is required for stdio type'); return; }
                payload.command = command;
                const argsText = document.getElementById('mcpServerArgs').value.trim();
                if (argsText) {
                    payload.args = argsText.split('\n').map(a => a.trim()).filter(a => a);
                }
            } else {
                const endpoint = document.getElementById('mcpServerEndpoint').value.trim();
                if (!endpoint) { alert('Endpoint is required'); return; }
                payload.endpoint = endpoint;
                payload.apiKey = document.getElementById('mcpServerApiKey').value.trim();
            }

            try {
                const response = await this.authFetch('/api/mcp', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });

                if (!response.ok) {
                    const err = await response.json();
                    alert(err.error || 'Failed to add server');
                    return;
                }
                this.loadMcpServers();
            } catch (error) {
                alert('Failed to add server: ' + error.message);
            }
        });
    }

    toggleMcpTypeFields() {
        const type = document.getElementById('mcpServerType').value;
        const isHttpType = type === 'sse' || type === 'streamable-http';
        document.getElementById('mcpSseFields').style.display = isHttpType ? '' : 'none';
        document.getElementById('mcpStdioFields').style.display = type === 'stdio' ? '' : 'none';
    }

    async showEditMcpServerModal(serverName) {
        try {
            const response = await this.authFetch('/api/mcp');
            const data = await response.json();
            const server = (data.servers || []).find(s => s.name === serverName);
            if (!server) { alert('Server not found'); return; }

            const serverType = server.type || 'sse';
            const isStdio = serverType === 'stdio';
            const isHttpType = serverType === 'sse' || serverType === 'streamable-http';
            const argsText = server.args ? server.args.join('\n') : '';

            this.showModal(`Edit: ${serverName}`, `
                <div class="form-group">
                    <label>Description</label>
                    <input class="form-control" id="editMcpDesc" value="${this.escapeHtml(server.description || '')}">
                </div>
                <div class="form-group">
                    <label>Transport Type</label>
                    <select class="form-control" id="editMcpType" onchange="app.toggleEditMcpTypeFields()">
                        <option value="sse" ${serverType === 'sse' ? 'selected' : ''}>SSE (HTTP)</option>
                        <option value="streamable-http" ${serverType === 'streamable-http' ? 'selected' : ''}>Streamable HTTP</option>
                        <option value="stdio" ${isStdio ? 'selected' : ''}>Stdio (Local Process)</option>
                    </select>
                </div>
                <div id="editMcpSseFields" style="${isHttpType ? '' : 'display:none'}">
                    <div class="form-group">
                        <label>Endpoint</label>
                        <input class="form-control" id="editMcpEndpoint" value="${this.escapeHtml(server.endpoint || '')}">
                    </div>
                    <div class="form-group">
                        <label>API Key</label>
                        <input class="form-control" id="editMcpApiKey" value="${this.escapeHtml(server.apiKey || '')}" placeholder="Leave unchanged to keep current key">
                    </div>
                </div>
                <div id="editMcpStdioFields" style="${isStdio ? '' : 'display:none'}">
                    <div class="form-group">
                        <label>Command</label>
                        <input class="form-control" id="editMcpCommand" value="${this.escapeHtml(server.command || '')}">
                    </div>
                    <div class="form-group">
                        <label>Arguments (one per line)</label>
                        <textarea class="form-control" id="editMcpArgs" rows="3">${this.escapeHtml(argsText)}</textarea>
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label>Timeout (ms)</label>
                        <input type="number" class="form-control" id="editMcpTimeout" value="${server.timeout}">
                    </div>
                    <div class="form-group">
                        <label>Enabled</label>
                        <select class="form-control" id="editMcpEnabled">
                            <option value="true" ${server.enabled ? 'selected' : ''}>Yes</option>
                            <option value="false" ${!server.enabled ? 'selected' : ''}>No</option>
                        </select>
                    </div>
                </div>
            `, async () => {
                const type = document.getElementById('editMcpType').value;
                const payload = {
                    type,
                    description: document.getElementById('editMcpDesc').value.trim(),
                    timeout: parseInt(document.getElementById('editMcpTimeout').value) || 30000,
                    enabled: document.getElementById('editMcpEnabled').value === 'true'
                };

                if (type === 'stdio') {
                    payload.command = document.getElementById('editMcpCommand').value.trim();
                    const argsVal = document.getElementById('editMcpArgs').value.trim();
                    if (argsVal) {
                        payload.args = argsVal.split('\n').map(a => a.trim()).filter(a => a);
                    }
                } else {
                    payload.endpoint = document.getElementById('editMcpEndpoint').value.trim();
                    payload.apiKey = document.getElementById('editMcpApiKey').value.trim();
                }

                try {
                    const updateResponse = await this.authFetch(`/api/mcp/${encodeURIComponent(serverName)}`, {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload)
                    });

                    if (!updateResponse.ok) {
                        const err = await updateResponse.json();
                        alert(err.error || 'Failed to update server');
                        return;
                    }
                    this.loadMcpServers();
                } catch (error) {
                    alert('Failed to update server: ' + error.message);
                }
            });
        } catch (error) {
            console.error('Failed to load server for editing:', error);
        }
    }

    toggleEditMcpTypeFields() {
        const type = document.getElementById('editMcpType').value;
        const isHttpType = type === 'sse' || type === 'streamable-http';
        document.getElementById('editMcpSseFields').style.display = isHttpType ? '' : 'none';
        document.getElementById('editMcpStdioFields').style.display = type === 'stdio' ? '' : 'none';
    }

    async testMcpServer(serverName) {
        const toolsSection = document.getElementById(`mcpTools-${serverName}`);
        if (!toolsSection) return;

        // 显示加载状态
        toolsSection.style.display = '';
        toolsSection.innerHTML = '<div class="mcp-tools-loading">🔄 Testing connection...</div>';

        try {
            const response = await this.authFetch(`/api/mcp/${encodeURIComponent(serverName)}/test`, {
                method: 'POST'
            });
            const data = await response.json();

            if (!data.success) {
                toolsSection.innerHTML = `
                    <div class="mcp-tools-error">
                        <span class="mcp-status-icon">❌</span>
                        <strong>Connection Failed</strong>
                        <p>${this.escapeHtml(data.error || 'Unknown error')}</p>
                    </div>`;
                return;
            }

            // 构建服务器信息
            let serverInfoHtml = '';
            if (data.serverInfo) {
                const parts = [];
                if (data.serverInfo.name) parts.push(data.serverInfo.name);
                if (data.serverInfo.version) parts.push(`v${data.serverInfo.version}`);
                if (data.serverInfo.protocolVersion) parts.push(`protocol ${data.serverInfo.protocolVersion}`);
                if (parts.length > 0) {
                    serverInfoHtml = `<div class="mcp-server-info">${this.escapeHtml(parts.join(' · '))}</div>`;
                }
            }

            // 构建工具列表
            const tools = data.tools || [];
            let toolsHtml = '';
            if (tools.length === 0) {
                toolsHtml = '<p class="mcp-no-tools">No tools available</p>';
            } else {
                toolsHtml = tools.map(tool => {
                    const params = (tool.parameters || []).map(p => {
                        const required = (tool.required || []).includes(p.name);
                        const typeLabel = p.type ? `<span class="mcp-param-type">${this.escapeHtml(p.type)}</span>` : '';
                        const requiredLabel = required ? '<span class="mcp-param-required">*</span>' : '';
                        return `<span class="mcp-param">${this.escapeHtml(p.name)}${requiredLabel}${typeLabel}</span>`;
                    }).join('');

                    return `
                        <div class="mcp-tool-item">
                            <div class="mcp-tool-name">🔧 ${this.escapeHtml(tool.name)}</div>
                            <div class="mcp-tool-desc">${this.escapeHtml(tool.description || '')}</div>
                            ${params ? `<div class="mcp-tool-params">${params}</div>` : ''}
                        </div>`;
                }).join('');
            }

            toolsSection.innerHTML = `
                <div class="mcp-tools-result">
                    <div class="mcp-tools-header">
                        <span class="mcp-status-icon">✅</span>
                        <strong>Connected</strong> — ${tools.length} tool${tools.length !== 1 ? 's' : ''} available
                        <button class="btn btn-text btn-sm" onclick="document.getElementById('mcpTools-${this.escapeHtml(serverName)}').style.display='none'" style="float:right">✕</button>
                    </div>
                    ${serverInfoHtml}
                    <div class="mcp-tools-list">${toolsHtml}</div>
                </div>`;

        } catch (error) {
            toolsSection.innerHTML = `
                <div class="mcp-tools-error">
                    <span class="mcp-status-icon">❌</span>
                    <strong>Error</strong>
                    <p>${this.escapeHtml(error.message)}</p>
                </div>`;
        }
    }

    async deleteMcpServer(serverName) {
        if (!confirm(`Delete MCP server "${serverName}"?`)) return;

        try {
            const response = await this.authFetch(`/api/mcp/${encodeURIComponent(serverName)}`, {
                method: 'DELETE'
            });

            if (!response.ok) {
                const err = await response.json();
                alert(err.error || 'Failed to delete server');
                return;
            }
            this.loadMcpServers();
        } catch (error) {
            alert('Failed to delete server: ' + error.message);
        }
    }

    // ==================== Models/Providers ====================

    async loadProviders() {
        try {
            // 加载 providers
            const providersResponse = await this.authFetch('/api/providers');
            const providers = await providersResponse.json();
            this.providers = providers;
            
            // 加载 models
            const modelsResponse = await this.authFetch('/api/models');
            const models = await modelsResponse.json();
            this.models = models;
            
            // 渲染 Provider 卡片
            const grid = document.getElementById('providersGrid');
            grid.innerHTML = providers.map(p => {
                const apiKeyDisplay = p.apiKey 
                    ? `<span class="provider-field-value masked">${this.maskApiKey(p.apiKey)}</span>`
                    : `<span class="provider-field-value not-set">Not set</span>`;
                const baseUrlDisplay = p.apiBase 
                    ? `<span class="provider-field-value" title="${p.apiBase}">${this.truncateUrl(p.apiBase)}</span>`
                    : `<span class="provider-field-value not-set">Not set</span>`;
                
                return `
                <div class="provider-card" data-provider="${p.name}">
                    <div class="provider-card-header">
                        <span class="provider-card-title">${this.capitalize(p.name)}</span>
                        <span class="badge ${p.authorized ? 'badge-success' : 'badge-disabled'}">
                            ${p.authorized ? 'Authorized' : 'Unauthorized'}
                        </span>
                    </div>
                    <div class="provider-card-body">
                        <div class="provider-field">
                            <span class="provider-field-label">Base URL:</span>
                            ${baseUrlDisplay}
                        </div>
                        <div class="provider-field">
                            <span class="provider-field-label">API Key:</span>
                            ${apiKeyDisplay}
                        </div>
                    </div>
                    <div class="provider-card-footer">
                        <button class="btn btn-text" onclick="app.editProvider('${p.name}')">✏️ Settings</button>
                    </div>
                </div>
                `;
            }).join('');
            
            // 更新 Provider 下拉框
            this.updateProviderSelect(providers);
        } catch (error) {
            console.error('Failed to load providers:', error);
        }
    }

    maskApiKey(apiKey) {
        if (!apiKey || apiKey.length < 8) return '****';
        return 'sk-' + '*'.repeat(16) + '...';
    }

    truncateUrl(url) {
        if (!url) return '';
        if (url.length > 25) {
            return url.substring(0, 25) + '...';
        }
        return url;
    }

    updateProviderSelect(providers) {
        const select = document.getElementById('providerSelect');
        const authorizedProviders = providers.filter(p => p.authorized);
        
        select.innerHTML = '<option value="">Select a provider</option>' +
            authorizedProviders.map(p => 
                `<option value="${p.name}">${this.capitalize(p.name)}</option>`
            ).join('');
    }

    updateModelSelect(providerName) {
        const select = document.getElementById('modelSelect');
        
        if (!providerName) {
            select.innerHTML = '<option value="">Select a model</option>';
            return;
        }
        
        // 过滤出属于指定 provider 的模型
        const providerModels = (this.models || []).filter(m => m.provider === providerName);
        
        select.innerHTML = '<option value="">Select a model</option>' +
            providerModels.map(m => {
                const displayName = m.description 
                    ? `${m.description} (${m.name})`
                    : `${this.formatModelName(m.name)} (${m.name})`;
                return `<option value="${m.name}">${displayName}</option>`;
            }).join('');
    }

    formatModelName(name) {
        // 将模型名格式化为更可读的形式
        return name.split('-').map(part => 
            part.charAt(0).toUpperCase() + part.slice(1)
        ).join(' ');
    }

    editProvider(name) {
        const provider = this.providers?.find(p => p.name === name) || {};
        
        this.showModal(`Edit ${this.capitalize(name)}`, `
            <div class="form-group">
                <label>API Key</label>
                <input class="form-control" id="modalApiKey" type="password" placeholder="Enter API key" value="${provider.apiKey || ''}">
            </div>
            <div class="form-group">
                <label>API Base URL (optional)</label>
                <input class="form-control" id="modalApiBase" placeholder="Leave empty for default" value="${provider.apiBase || ''}">
            </div>
        `, async () => {
            const data = {};
            const apiKey = document.getElementById('modalApiKey').value;
            const apiBase = document.getElementById('modalApiBase').value;
            if (apiKey) data.apiKey = apiKey;
            data.apiBase = apiBase || '';
            
            await this.authFetch(`/api/providers/${name}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            this.loadProviders();
            this.loadCurrentModel();
        });
    }

    async loadCurrentModel() {
        try {
            const response = await this.authFetch('/api/config/model');
            const data = await response.json();
            const model = data.model || '';
            const provider = data.provider || '';
            
            document.getElementById('providerSelect').value = provider;
            // 先更新 Model 下拉框选项
            this.updateModelSelect(provider);
            // 再设置当前值
            document.getElementById('modelSelect').value = model;
            
            // 更新激活状态徽章
            if (provider && model) {
                document.getElementById('activeModelBadge').textContent = `Active: ${provider} / ${model}`;
            } else if (model) {
                document.getElementById('activeModelBadge').textContent = `Active: ${model}`;
            } else {
                document.getElementById('activeModelBadge').textContent = 'Active: -';
            }
            
            // 标记当前选中的 Provider 卡片
            this.highlightSelectedProvider(provider);
        } catch (error) {
            console.error('Failed to load model:', error);
        }

        // 绑定事件
        this.bindModelConfigEvents();
    }

    highlightSelectedProvider(providerName) {
        document.querySelectorAll('.provider-card').forEach(card => {
            card.classList.toggle('selected', card.dataset.provider === providerName);
        });
    }

    bindModelConfigEvents() {
        const providerSelect = document.getElementById('providerSelect');
        const modelSelect = document.getElementById('modelSelect');
        const saveBtn = document.getElementById('saveModelBtn');
        
        let originalProvider = providerSelect.value;
        let originalModel = modelSelect.value;
        
        const checkChanges = () => {
            const hasChanges = providerSelect.value !== originalProvider || 
                              modelSelect.value !== originalModel;
            if (hasChanges) {
                saveBtn.disabled = false;
                saveBtn.classList.remove('btn-success');
                saveBtn.classList.add('btn-primary');
                saveBtn.innerHTML = 'Save';
            } else {
                saveBtn.disabled = true;
                saveBtn.classList.remove('btn-primary');
                saveBtn.classList.add('btn-success');
                saveBtn.innerHTML = '<span class="btn-icon">✓</span> Saved';
            }
        };
        
        providerSelect.onchange = () => {
            // 当 Provider 改变时，更新 Model 下拉框
            this.updateModelSelect(providerSelect.value);
            checkChanges();
            this.highlightSelectedProvider(providerSelect.value);
        };
        modelSelect.onchange = checkChanges;
        
        saveBtn.onclick = async () => {
            const provider = providerSelect.value;
            const model = modelSelect.value;
            
            await this.authFetch('/api/config/model', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ model, provider })
            });
            
            // 更新状态
            originalProvider = provider;
            originalModel = model;
            checkChanges();
            
            // 更新徽章
            if (provider && model) {
                document.getElementById('activeModelBadge').textContent = `Active: ${provider} / ${model}`;
            } else if (model) {
                document.getElementById('activeModelBadge').textContent = `Active: ${model}`;
            }
        };
        
        // 初始化按钮状态
        checkChanges();
    }

    // ==================== Environments ====================

    async loadAgentConfig() {
        try {
            const response = await this.authFetch('/api/config/agent');
            const config = await response.json();
            
            document.getElementById('cfgMaxTokens').value = config.maxTokens;
            document.getElementById('cfgTemperature').value = config.temperature;
            document.getElementById('cfgMaxToolIterations').value = config.maxToolIterations;
            document.getElementById('cfgHeartbeatEnabled').value = config.heartbeatEnabled.toString();
            document.getElementById('cfgRestrictToWorkspace').value = config.restrictToWorkspace.toString();
        } catch (error) {
            console.error('Failed to load agent config:', error);
        }

        document.getElementById('saveAgentConfigBtn').onclick = async () => {
            const data = {
                maxTokens: parseInt(document.getElementById('cfgMaxTokens').value),
                temperature: parseFloat(document.getElementById('cfgTemperature').value),
                maxToolIterations: parseInt(document.getElementById('cfgMaxToolIterations').value),
                heartbeatEnabled: document.getElementById('cfgHeartbeatEnabled').value === 'true',
                restrictToWorkspace: document.getElementById('cfgRestrictToWorkspace').value === 'true'
            };
            
            await this.authFetch('/api/config/agent', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            alert('Configuration saved!');
        };
    }

    // ==================== Modal ====================

    bindModal() {
        document.getElementById('modalClose').onclick = () => this.hideModal();
        document.getElementById('modalCancel').onclick = () => this.hideModal();
        document.getElementById('modal').onclick = (e) => {
            if (e.target.id === 'modal') this.hideModal();
        };
    }

    showModal(title, content, onConfirm) {
        document.getElementById('modalTitle').textContent = title;
        document.getElementById('modalBody').innerHTML = content;
        document.getElementById('modalConfirm').style.display = onConfirm ? 'block' : 'none';
        document.getElementById('modalConfirm').onclick = async () => {
            if (onConfirm) await onConfirm();
            this.hideModal();
        };
        document.getElementById('modal').classList.add('active');
    }

    hideModal() {
        document.getElementById('modal').classList.remove('active');
    }

    // ==================== Helpers ====================

    capitalize(str) {
        return str.charAt(0).toUpperCase() + str.slice(1);
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // ==================== Token 消耗 ====================

    /**
     * 初始化并加载 Token 消耗页面。
     * 设置默认日期范围（最近 30 天），绑定刷新按钮，然后拉取数据。
     */
    async loadTokenUsage() {
        const today = new Date();
        const twoDaysAgo = new Date(today);
        twoDaysAgo.setDate(today.getDate() - 2);

        const formatDate = (date) => {
            const year = date.getFullYear();
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const day = String(date.getDate()).padStart(2, '0');
            return `${year}-${month}-${day}`;
        };

        const startInput = document.getElementById('tokenStartDate');
        const endInput = document.getElementById('tokenEndDate');

        startInput.value = formatDate(twoDaysAgo);
        endInput.value = formatDate(today);

        // 绑定刷新按钮（避免重复绑定）
        const refreshBtn = document.getElementById('tokenRefreshBtn');
        refreshBtn.onclick = () => this.fetchTokenStats();

        await this.fetchTokenStats();
    }

    /**
     * 从后端拉取 Token 消耗统计数据并渲染页面。
     */
    async fetchTokenStats() {
        const startDate = document.getElementById('tokenStartDate').value;
        const endDate = document.getElementById('tokenEndDate').value;

        if (!startDate || !endDate) return;

        try {
            const response = await this.authFetch(
                `/api/token-stats?startDate=${startDate}&endDate=${endDate}`
            );
            if (!response.ok) {
                console.error('Failed to fetch token stats:', response.status);
                return;
            }
            const data = await response.json();
            this.renderTokenStats(data);
        } catch (error) {
            console.error('Token stats fetch error:', error);
        }
    }

    /**
     * 将 Token 统计数据渲染到页面上。
     *
     * @param {Object} data - 后端返回的统计数据
     */
    renderTokenStats(data) {
        // 渲染总量汇总
        document.getElementById('tokenTotalPrompt').textContent =
            this.formatTokenCount(data.totalPromptTokens || 0);
        document.getElementById('tokenTotalCompletion').textContent =
            this.formatTokenCount(data.totalCompletionTokens || 0);

        // 渲染按模型分组表格
        const byModelBody = document.getElementById('tokenByModelBody');
        const byModel = data.byModel || [];
        if (byModel.length === 0) {
            byModelBody.innerHTML = '<tr><td colspan="5" class="empty-state">暂无数据</td></tr>';
        } else {
            byModelBody.innerHTML = byModel.map(row => `
                <tr>
                    <td><strong>${this.escapeHtml(row.provider)}</strong></td>
                    <td>${this.escapeHtml(row.model)}</td>
                    <td>${this.formatTokenCount(row.promptTokens)}</td>
                    <td>${this.formatTokenCount(row.completionTokens)}</td>
                    <td>${row.callCount}</td>
                </tr>
            `).join('');
        }

        // 渲染按日期分组表格
        const byDateBody = document.getElementById('tokenByDateBody');
        const byDate = data.byDate || [];
        if (byDate.length === 0) {
            byDateBody.innerHTML = '<tr><td colspan="4" class="empty-state">暂无数据</td></tr>';
        } else {
            byDateBody.innerHTML = byDate.map(row => `
                <tr>
                    <td><strong>${this.escapeHtml(row.date)}</strong></td>
                    <td>${this.formatTokenCount(row.promptTokens)}</td>
                    <td>${this.formatTokenCount(row.completionTokens)}</td>
                    <td>${row.callCount}</td>
                </tr>
            `).join('');
        }
    }

    /**
     * 将 token 数量格式化为易读形式（如 19700 → 19.7K）。
     *
     * @param {number} count - token 数量
     * @returns {string} 格式化后的字符串
     */
    formatTokenCount(count) {
        if (count >= 1_000_000) {
            return (count / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
        }
        if (count >= 1_000) {
            return (count / 1_000).toFixed(1).replace(/\.0$/, '') + 'K';
        }
        return String(count);
    }
}

// Initialize app
const app = new TinyClawConsole();
