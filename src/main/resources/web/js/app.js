// TinyClaw Web Console - App

class TinyClawConsole {
    constructor() {
        this.currentPage = 'chat';
        this.chatSessionId = localStorage.getItem('tinyclaw_chat_session') || 'web:default';
        this.allSessions = [];
        this.currentSessionPage = 1;
        this.authToken = localStorage.getItem('tinyclaw_token') || null;
        // 后端能力开关：显式反馈（👍/👎）是否启用，由 fetchCapabilities 探测
        this.feedbackEnabled = false;
        this.init();
    }

    init() {
        // 配置 Markdown 渲染：不开启 breaks，避免列表等块级元素产生多余 <br> 间距
        // 流式渲染阶段用 CSS white-space: pre-wrap 处理换行，finalizeCurrentText 后由 marked 正确渲染
        if (typeof marked !== 'undefined') {
            marked.setOptions({ breaks: false });
        }
        
        this.bindThemeToggle();
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
            options.headers['Authorization'] = 'Bearer ' + this.authToken;
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
                const app = document.querySelector('.app');
                if (app.classList.contains('sidebar-collapsed')) {
                    // 折叠态下点击分组图标：展开侧边栏并展开该分组
                    app.classList.remove('sidebar-collapsed');
                    localStorage.setItem('tinyclaw_sidebar_collapsed', '0');
                    group.classList.remove('collapsed');
                    return;
                }
                group.classList.toggle('collapsed');
            });
        });

        // Sidebar collapse/expand
        const app = document.querySelector('.app');
        if (localStorage.getItem('tinyclaw_sidebar_collapsed') === '1') {
            app.classList.add('sidebar-collapsed');
        }
        document.getElementById('sidebarToggle').addEventListener('click', () => {
            const collapsed = app.classList.toggle('sidebar-collapsed');
            localStorage.setItem('tinyclaw_sidebar_collapsed', collapsed ? '1' : '0');
        });

        // Hash change
        window.addEventListener('hashchange', () => {
            const page = window.location.hash.slice(1) || 'chat';
            this.navigateTo(page, false);
        });
    }

    // ==================== Theme ====================

    /**
     * 绑定主题切换按钮：在浅色 / 深色间切换并持久化到 localStorage。
     * 默认浅色；首屏的实际应用由 index.html <head> 内联脚本完成（防闪烁），此处负责交互与兜底。
     */
    bindThemeToggle() {
        const btn = document.getElementById('themeToggle');
        // 兜底：即便内联脚本未生效，也确保进入时应用已保存主题（默认浅色）
        this.applyTheme(localStorage.getItem('tinyclaw_theme') === 'dark' ? 'dark' : 'light');
        if (!btn) return;
        btn.addEventListener('click', () => {
            const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
            this.applyTheme(next);
            localStorage.setItem('tinyclaw_theme', next);
        });
    }

    applyTheme(theme) {
        document.documentElement.dataset.theme = theme;
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
            dashboard: 'Dashboard',
            chat: 'Chat',
            channels: 'Channels',
            sessions: 'Sessions',
            cron: 'Cron Jobs',
            workspace: 'Workspace',
            skills: 'Skills',
            mcp: 'MCP Servers',
            'tools-health': 'Tools Health',
            memory: 'Memory',
            projects: 'Projects',
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
        // 先探测能力开关（如显式反馈是否启用）再渲染首屏，避免消息操作栏状态竞态
        this.fetchCapabilities().finally(() => {
            const page = window.location.hash.slice(1) || 'chat';
            this.navigateTo(page, false);
        });
    }

    /**
     * 探测后端能力开关：目前用于判断显式反馈（👍/👎）是否启用，
     * 未启用（进化关闭）时消息操作栏不渲染反馈按钮，避免点击必然报错。
     */
    async fetchCapabilities() {
        try {
            const resp = await this.authFetch('/api/feedback');
            if (resp.ok) {
                const data = await resp.json();
                this.feedbackEnabled = !!data.feedbackEnabled;
            }
        } catch (e) {
            this.feedbackEnabled = false;
        }
    }

    loadPageData(page) {
        switch (page) {
            case 'dashboard': this.loadDashboard(); break;
            case 'chat': this.loadChatHistory(); this.loadChatSessions(); this.loadChatInputMeta(); break;
            case 'channels': this.loadChannels(); break;
            case 'sessions': this.loadSessions(); break;
            case 'cron': this.loadCronJobs(); break;
            case 'workspace': this.loadWorkspaceFiles(); break;
            case 'skills': this.loadSkills(); break;
            case 'mcp': this.loadMcpServers(); break;
            case 'tools-health': this.loadReflection(); break;
            case 'memory': this.loadMemory(); break;
            case 'projects': this.loadProjects(); break;
            case 'models': this.loadProviders(); this.loadCurrentModel(); break;
            case 'environments': this.loadAgentConfig(); break;
            case 'token-usage': this.loadTokenUsage(); break;
        }
    }

    // ==================== Chat ====================

    // 待上传的图片列表（存储 Base64 数据）
    pendingImages = [];
    // P2 待发送的文档附件列表：{ id, name, status, truncated, parseError }
    pendingAttachments = [];
    // P2 附件状态轮询定时器
    _attachmentPollTimer = null;
    // fork 重新生成时，原始提问已上传的图片路径（跳过 base64 重传，由 sendMessage 消费）
    _replayImagePaths = null;
    // 本次会话中 Agent 通过 write_file/edit_file 触达的文件（Artifacts 面板）
    sessionArtifacts = [];
    // 当前正在执行的任务的 AbortController（用于中断）
    currentAbortController = null;
    // 会话历史加载序号：切换会话后旧异步响应不再写入新会话 DOM
    _chatHistoryLoadSeq = 0;
    // 草稿防抖定时器（输入停顿后落盘 localStorage）
    _draftSaveTimer = null;
    // 流式新消息计数：用户上滑阅读时收到新内容则点亮“回到最新”徽标
    _newMsgCount = 0;
    // Slash command menu state
    slashMenuVisible = false;
    slashMenuIndex = -1;
    slashMenuItems = [];
    skillsCache = null;

    bindChat() {
        const input = document.getElementById('chatInput');
        const sendBtn = document.getElementById('sendBtn');
        const newChatBtn = document.getElementById('newChatBtn');
        const uploadBtn = document.getElementById('uploadBtn');
        const imageUpload = document.getElementById('imageUpload');

        sendBtn.addEventListener('click', () => this.sendMessage());
        input.addEventListener('keydown', (e) => {
            // Slash menu 键盘导航
            if (this.slashMenuVisible) {
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Tab') {
                    e.preventDefault();
                    this.handleSlashMenuKey(e.key);
                    return;
                }
                if (e.key === 'Enter') {
                    e.preventDefault();
                    this.handleSlashMenuKey('Enter');
                    return;
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    this.hideSlashMenu();
                    return;
                }
            }
            // Ctrl+Enter (Windows/Linux) 或 Cmd+Enter (Mac) 发送消息
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                this.sendMessage();
            }
        });

        input.addEventListener('input', () => {
            input.style.height = 'auto';
            input.style.height = Math.min(input.scrollHeight, 120) + 'px';
            this.handleSlashInput(input);
        });

        // 点击外部关闭 slash menu
        document.addEventListener('click', (e) => {
            if (this.slashMenuVisible) {
                const menu = document.getElementById('slashMenu');
                if (menu && !menu.contains(e.target) && e.target !== input) {
                    this.hideSlashMenu();
                }
            }
        });

        newChatBtn.addEventListener('click', () => this.createNewChatSession());

        // 侧栏历史消息搜索
        this.bindChatSearch();

        // 图片上传按钮
        uploadBtn.addEventListener('click', () => imageUpload.click());
        imageUpload.addEventListener('change', (e) => this.handleImageSelect(e));

        // P2 文档附件按钮：逐文件二进制上传，解析状态轮询
        const fileUploadBtn = document.getElementById('fileUploadBtn');
        const fileUpload = document.getElementById('fileUpload');
        if (fileUploadBtn && fileUpload) {
            fileUploadBtn.addEventListener('click', () => fileUpload.click());
            fileUpload.addEventListener('change', (e) => this.handleDocumentSelect(e));
        }

        // Artifacts（本次会话产生的文件）按钮
        const artifactsBtn = document.getElementById('artifactsBtn');
        if (artifactsBtn) artifactsBtn.addEventListener('click', () => this.openArtifactsPanel());

        // P6：把本轮对话设为周期任务（入口在聊天输入区，先发一次消息再操作）
        const scheduleBtn = document.getElementById('scheduleBtn');
        if (scheduleBtn) scheduleBtn.addEventListener('click', () => this.showScheduleFromChat());

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

        // P0 体验基础：草稿、滚动指示、移动端抽屉、输入区模型信息
        this.bindDrafts();
        this.bindScrollIndicators();
        this.bindChatDrawer();
        this.loadChatInputMeta();
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
        // 保存旧会话草稿，新会话从空白输入开始
        this.saveCurrentInputAsDraft();
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
        this.restoreDraft();
        this.closeChatDrawer();
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

    // ==================== P2：通用文档附件 ====================

    /**
     * 文件选择：逐文件上传（原始二进制），加入待发送列表并开始状态轮询。
     * 单份失败不影响其他附件；超过 5 个时提示并拒绝追加。
     */
    handleDocumentSelect(e) {
        const files = e.target.files;
        if (files) {
            this.uploadDocuments(Array.from(files));
        }
        e.target.value = '';
    }

    /**
     * 逐文件上传文档：POST /api/attachments（二进制体）。
     */
    async uploadDocuments(files) {
        for (const file of files) {
            if (this.pendingAttachments.length >= 5) {
                this.showToast('每轮最多 5 个附件', 'info');
                break;
            }
            if (file.size > 10 * 1024 * 1024) {
                this.showToast(`${file.name} 超过 10 MiB 上限，未上传`, 'error');
                continue;
            }
            // 先以 UPLOADING 占位，上传响应后替换
            const placeholder = { id: null, name: file.name, status: 'UPLOADING', truncated: false, parseError: '' };
            this.pendingAttachments.push(placeholder);
            this.updateAttachmentChips();
            try {
                const resp = await this.authFetch('/api/attachments?sessionId='
                    + encodeURIComponent(this.chatSessionId)
                    + '&name=' + encodeURIComponent(file.name), {
                    method: 'POST',
                    headers: { 'Content-Type': file.type || 'application/octet-stream' },
                    body: file
                });
                const data = await resp.json();
                if (!resp.ok) throw new Error(data.error || ('HTTP ' + resp.status));
                Object.assign(placeholder, {
                    id: data.id,
                    name: data.name,
                    status: data.status,
                    truncated: !!data.truncated,
                    parseError: data.parseError || ''
                });
            } catch (err) {
                placeholder.status = 'FAILED';
                placeholder.parseError = err.message;
                this.showToast(`${file.name} 上传失败：${err.message}`, 'error');
            }
            this.updateAttachmentChips();
        }
        this.startAttachmentPolling();
    }

    /**
     * 轮询未就绪附件的解析状态（800ms 间隔；全部终态后停止）。
     */
    startAttachmentPolling() {
        clearInterval(this._attachmentPollTimer);
        this._attachmentPollTimer = setInterval(async () => {
            const pending = this.pendingAttachments.filter(a => a.id && a.status === 'PARSING');
            if (pending.length === 0) {
                clearInterval(this._attachmentPollTimer);
                this._attachmentPollTimer = null;
                return;
            }
            for (const att of pending) {
                try {
                    const resp = await this.authFetch('/api/attachments/' + att.id);
                    if (!resp.ok) continue;
                    const data = await resp.json();
                    att.status = data.status;
                    att.truncated = !!data.truncated;
                    att.parseError = data.parseError || '';
                    if (data.status === 'FAILED' && att.parseError) {
                        this.showToast(`附件「${att.name}」解析失败：${att.parseError}`, 'error');
                    }
                } catch (e) { /* 单次轮询失败不中断 */ }
            }
            this.updateAttachmentChips();
        }, 800);
    }

    /**
     * 移除一个待发送附件（不因一份失败清空其他）。
     */
    removePendingAttachment(index) {
        this.pendingAttachments.splice(index, 1);
        this.updateAttachmentChips();
    }

    /**
     * 渲染附件 chip 区：状态图标（解析中旋转/就绪✓/失败✗）、截断标记、移除按钮。
     */
    updateAttachmentChips() {
        const box = document.getElementById('chatAttachments');
        if (!box) return;
        if (this.pendingAttachments.length === 0) {
            box.style.display = 'none';
            box.innerHTML = '';
            return;
        }
        box.style.display = 'flex';
        box.innerHTML = this.pendingAttachments.map((att, idx) => {
            const statusIcon = att.status === 'READY' ? '✓'
                : att.status === 'FAILED' ? '✗'
                    : att.status === 'PARSING' ? '<span class="tool-call-spinner"></span>'
                        : '⬆';
            const statusCls = att.status === 'READY' ? 'att-ready'
                : att.status === 'FAILED' ? 'att-failed' : 'att-pending';
            const truncatedTag = att.truncated ? '<span class="att-truncated" title="内容过长已截断">截断</span>' : '';
            const failedTip = att.status === 'FAILED' && att.parseError
                ? ` title="${this.escapeAttr(att.parseError)}"` : '';
            return `<div class="attachment-chip ${statusCls}"${failedTip}>
                <span class="att-status">${statusIcon}</span>
                <span class="att-name" title="${this.escapeAttr(att.name)}">${this.escapeHtml(att.name)}</span>
                ${truncatedTag}
                <button class="att-remove" data-idx="${idx}" title="移除">×</button>
            </div>`;
        }).join('');
        box.querySelectorAll('.att-remove').forEach(btn => {
            btn.addEventListener('click', () => this.removePendingAttachment(Number(btn.dataset.idx)));
        });
    }

    /**
     * 取就绪附件的 ID 列表（发送时用；未就绪/失败的被跳过并在发送后提示）。
     */
    readyAttachmentIds() {
        return this.pendingAttachments.filter(a => a.status === 'READY' && a.id).map(a => a.id);
    }

    /**
     * 清空待发送附件。
     */
    clearPendingAttachments() {
        this.pendingAttachments = [];
        clearInterval(this._attachmentPollTimer);
        this._attachmentPollTimer = null;
        this.updateAttachmentChips();
    }

    // ==================== P0：草稿 / 滚动 / 抽屉 / 输入区信息 ====================

    /**
     * 草稿存储键：按当前会话隔离（同一浏览器即同一实例，localStorage 天然按实例分隔）。
     * 只存正文文本；尚未上传的图片不落盘，刷新后提示重新选择，不伪称已保存。
     */
    draftKey() {
        return 'tinyclaw_draft:' + this.chatSessionId;
    }

    /**
     * 防抖保存草稿：输入停顿 300ms 后落盘，避免逐键写 localStorage。
     */
    bindDrafts() {
        const input = document.getElementById('chatInput');
        if (!input) return;
        input.addEventListener('input', () => {
            clearTimeout(this._draftSaveTimer);
            this._draftSaveTimer = setTimeout(() => this.saveDraft(input.value), 300);
        });
        // 初始恢复当前会话草稿
        this.restoreDraft();
    }

    /**
     * 保存草稿（仅正文；空文本直接清除存储）。
     */
    saveDraft(text) {
        try {
            if (text && text.trim()) {
                localStorage.setItem(this.draftKey(), text);
            } else {
                localStorage.removeItem(this.draftKey());
            }
        } catch (e) {
            console.warn('Failed to save draft:', e);
        }
    }

    /**
     * 恢复草稿到输入框（有草稿时调整自适应高度）。
     */
    restoreDraft() {
        const input = document.getElementById('chatInput');
        if (!input) return;
        let draft = '';
        try {
            draft = localStorage.getItem(this.draftKey()) || '';
        } catch (e) { /* 存储不可用时降级为无草稿 */ }
        input.value = draft;
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
    }

    /**
     * 清除草稿：仅服务端确认接收后调用。
     */
    clearDraft() {
        clearTimeout(this._draftSaveTimer);
        try {
            localStorage.removeItem(this.draftKey());
        } catch (e) { /* 忽略 */ }
    }

    /**
     * 发送失败时把正文放回输入框并保存草稿。
     * 注意不自动重发：连接中断时消息可能已送达，重发前需用户确认。
     */
    restoreFailedInput(message) {
        if (!message) return;
        const input = document.getElementById('chatInput');
        if (input) {
            input.value = message;
            input.style.height = 'auto';
            input.style.height = Math.min(input.scrollHeight, 120) + 'px';
            input.focus();
        }
        this.saveDraft(message);
    }

    /**
     * 统一读取 API 错误体：兼容 {error} / {message} / 空体，区分失败与可重试。
     */
    async readApiError(resp) {
        let detail = '';
        try {
            const data = await resp.json();
            detail = data.error || data.message || '';
        } catch (e) { /* 无 JSON 体时使用状态码 */ }
        const retryable = resp.status >= 500 || resp.status === 429;
        const suffix = retryable ? '，可稍后重试' : '';
        return (detail || ('请求失败 (' + resp.status + ')')) + suffix;
    }

    /**
     * 判断用户是否停留在消息区底部（阈值 120px）。
     * 流式输出仅在底部时自动滚动，阅读历史不被新 token 强制拉到底。
     */
    isNearBottom(el, threshold = 120) {
        return el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
    }

    /**
     * 绑定滚动指示：用户上滑时显示“回到最新”按钮，用户不在底部时新内容点亮徽标。
     */
    bindScrollIndicators() {
        const messagesDiv = document.getElementById('chatMessages');
        const btn = document.getElementById('backToBottomBtn');
        if (!messagesDiv || !btn) return;
        messagesDiv.addEventListener('scroll', () => {
            const near = this.isNearBottom(messagesDiv);
            btn.classList.toggle('visible', !near);
            if (near) this.hideNewMsgBadge();
        }, { passive: true });
        btn.addEventListener('click', () => {
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
            btn.classList.remove('visible');
            this.hideNewMsgBadge();
        });
    }

    /**
     * 流式输出到达但用户不在底部：徽标计一条新消息。
     */
    notifyNewStreamContent() {
        const messagesDiv = document.getElementById('chatMessages');
        const btn = document.getElementById('backToBottomBtn');
        if (!messagesDiv || !btn) return;
        if (this.isNearBottom(messagesDiv)) {
            this.hideNewMsgBadge();
            return;
        }
        this._newMsgCount++;
        const badge = document.getElementById('backToBottomBadge');
        if (badge) {
            badge.style.display = '';
            badge.textContent = this._newMsgCount > 9 ? '9+' : String(this._newMsgCount);
        }
    }

    /**
     * 清零新消息徽标。
     */
    hideNewMsgBadge() {
        this._newMsgCount = 0;
        const badge = document.getElementById('backToBottomBadge');
        if (badge) {
            badge.style.display = 'none';
            badge.textContent = '';
        }
    }

    /**
     * 绑定移动端会话抽屉：小屏下恢复新建/搜索/历史入口。
     * Escape 关闭并归还焦点；backdrop 点击关闭；会话项支持 Enter 键导航。
     */
    bindChatDrawer() {
        const btn = document.getElementById('chatDrawerBtn');
        const backdrop = document.getElementById('chatDrawerBackdrop');
        if (!btn) return;
        btn.addEventListener('click', () => this.openChatDrawer());
        if (backdrop) {
            backdrop.addEventListener('click', () => this.closeChatDrawer());
        }
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                const sidebar = document.querySelector('.chat-sidebar');
                if (sidebar && sidebar.classList.contains('drawer-open')) {
                    e.preventDefault();
                    this.closeChatDrawer();
                }
            }
        });
    }

    /**
     * 打开移动端抽屉：焦点移入搜索框，便于键盘导航。
     */
    openChatDrawer() {
        const sidebar = document.querySelector('.chat-sidebar');
        const backdrop = document.getElementById('chatDrawerBackdrop');
        if (!sidebar) return;
        sidebar.classList.add('drawer-open');
        if (backdrop) backdrop.classList.add('visible');
        const search = document.getElementById('chatSearchInput');
        if (search) search.focus();
    }

    /**
     * 关闭移动端抽屉：焦点返回触发按钮。
     */
    closeChatDrawer() {
        const sidebar = document.querySelector('.chat-sidebar');
        const backdrop = document.getElementById('chatDrawerBackdrop');
        if (!sidebar || !sidebar.classList.contains('drawer-open')) return;
        sidebar.classList.remove('drawer-open');
        if (backdrop) backdrop.classList.remove('visible');
        const btn = document.getElementById('chatDrawerBtn');
        if (btn) btn.focus();
    }

    /**
     * 加载输入区全局模型与思考配置提示。
     * 明确其全局作用范围（非会话级），点击 chip 跳转 Models 设置页。
     */
    async loadChatInputMeta() {
        const metaDiv = document.getElementById('chatInputMeta');
        if (!metaDiv) return;
        try {
            const [modelResp, agentResp] = await Promise.allSettled([
                this.authFetch('/api/config/model'),
                this.authFetch('/api/config/agent')
            ]);
            const model = modelResp.status === 'fulfilled' && modelResp.value.ok
                ? await modelResp.value.json() : {};
            const agent = agentResp.status === 'fulfilled' && agentResp.value.ok
                ? await agentResp.value.json() : {};
            const modelName = model.model || '未设置';
            const providerName = model.provider || '';
            const label = providerName ? `${providerName} / ${modelName}` : modelName;
            // P5：缓存模型标签（本轮上下文摘要面板展示用）
            this._currentModelLabel = label;
            const thinking = agent.thinkingEnabled === true ? '思考开' : '思考关';
            // 作用范围（全局）信息收进 title 提示，避免第三个独立 chip 拼接 clutter
            metaDiv.innerHTML =
                `<button class="meta-chip" id="modelMetaChip" title="全局模型配置，对所有会话生效，点击前往设置">🤖 ${this.escapeHtml(label)}</button>`
                + `<span class="meta-chip meta-chip-static" title="全局思考模式配置，对所有会话生效">✦ ${thinking}</span>`;
            const chip = document.getElementById('modelMetaChip');
            if (chip) {
                chip.addEventListener('click', () => this.navigateTo('models'));
            }
        } catch (e) {
            metaDiv.innerHTML = '<span class="meta-chip meta-chip-static" title="全局模型配置">模型配置未加载</span>';
        }
    }

    // ==================== P5：本轮上下文透明化与会话记忆模式 ====================

    /** 当前会话记忆模式缓存（loadChatSessions 时更新）。 */
    _sessionMemoryMode = 'DEFAULT';

    /** 本轮使用记忆面板展开状态。 */
    _contextUsedExpanded = false;

    /** 最近一次 context-used 数据（展开/收起重渲染用）。 */
    _lastContextUsed = null;

    /** 会话是否存在压缩摘要（loadChatHistory 时更新）。 */
    _sessionHasSummary = false;

    /** 全局模型标签缓存（loadChatInputMeta 时更新，上下文摘要面板展示用）。 */
    _currentModelLabel = '';

    /**
     * 拉取当前会话最近一次记忆选择（本轮使用）：
     * 与真实注入系统提示词的文本来自同一次服务端计算，不做访问次数推断。
     * 每轮流结束与切换会话后刷新。
     */
    async loadMemoryUsed() {
        const bar = document.getElementById('contextUsedBar');
        if (!bar) return;
        try {
            const resp = await this.authFetch('/api/memory/context-used?sessionId='
                + encodeURIComponent(this.chatSessionId));
            if (!resp.ok) {
                bar.style.display = 'none';
                return;
            }
            const data = await resp.json();
            this._lastContextUsed = data;
            this.renderContextUsedBar();
        } catch (e) {
            // 网络错误时静默隐藏，不打断聊天
            bar.style.display = 'none';
        }
    }

    /**
     * 渲染本轮上下文条：使用记忆数/主题数/token 估算 + 会话记忆模式徽标；
     * 展开后列出实际使用的记忆（可纠正/删除，仅影响后续轮次）与上下文构成摘要。
     */
    renderContextUsedBar() {
        const bar = document.getElementById('contextUsedBar');
        if (!bar) return;
        const data = this._lastContextUsed;
        const memoryOff = this._sessionMemoryMode === 'OFF';
        if (!data || !data.available) {
            // 尚无登记（未发过消息）：仅在 OFF 时展示模式徽标
            if (memoryOff) {
                bar.style.display = 'flex';
                bar.innerHTML = '<span class="ctx-used-chip ctx-used-off">'
                    + '不使用长期记忆 · 聊天历史仍会保存</span>';
            } else {
                bar.style.display = 'none';
            }
            return;
        }
        bar.style.display = 'flex';
        const off = memoryOff || data.disabled === true;
        let head;
        if (off) {
            head = '<span class="ctx-used-chip ctx-used-off">'
                + '不使用长期记忆 · 本轮未注入任何长期记忆</span>';
        } else {
            const topicsPart = data.topicCount ? ` · 主题 ${data.topicCount} 个` : '';
            const tokenPart = data.estimatedTokens ? ` · ≈${data.estimatedTokens} tokens（估算）` : '';
            head = `<button class="ctx-used-chip ctx-used-toggle" title="展开查看实际使用的记忆">`
                + `🧠 本轮使用记忆 ${data.memoryCount || 0} 条${topicsPart}${tokenPart}`
                + `${this._contextUsedExpanded ? ' ▴' : ' ▾'}</button>`;
        }
        let detail = '';
        if (this._contextUsedExpanded && !off) {
            detail = this.renderContextUsedDetail(data);
        }
        bar.innerHTML = head + detail;
        const toggle = bar.querySelector('.ctx-used-toggle');
        if (toggle) {
            toggle.addEventListener('click', () => {
                this._contextUsedExpanded = !this._contextUsedExpanded;
                this.renderContextUsedBar();
            });
        }
        this.bindContextUsedActions();
    }

    /** 展开区：实际使用的记忆列表（纠正/删除）+ 上下文构成摘要。 */
    renderContextUsedDetail(data) {
        const entries = Array.isArray(data.entries) ? data.entries : [];
        const topics = Array.isArray(data.topics) ? data.topics : [];
        const listHtml = entries.length
            ? entries.map(e => `
                <div class="ctx-used-item" data-mem-id="${this.escapeAttr(e.id)}">
                    <div class="ctx-used-item-head">
                        <span class="badge badge-outline">${this.escapeHtml(e.scope || 'global')}</span>
                        <span class="ctx-used-meta">${this.escapeHtml(e.source || '')}</span>
                    </div>
                    <div class="ctx-used-content">${this.escapeHtml(e.content || '')}</div>
                    <div class="ctx-used-actions">
                        <button class="btn btn-text btn-sm" data-act="correct">纠正</button>
                        <button class="btn btn-text btn-danger btn-sm" data-act="forget">删除</button>
                    </div>
                </div>`).join('')
            : '<p class="empty-state">本轮没有选中任何结构化记忆</p>';
        const topicsHtml = topics.length
            ? `<div class="ctx-used-topics">主题文件：${topics.map(t => `<span class="memory-tag">${this.escapeHtml(t)}</span>`).join('')}</div>`
            : '';
        // 上下文构成摘要（事项 7）：模型 / 附件 / 摘要 / 记忆，估算值与实际用量分开标注
        const attachCount = (this.pendingAttachments || []).length;
        const summaryState = this._sessionHasSummary ? '使用压缩摘要' : '无压缩摘要';
        const modelLabel = this._currentModelLabel || '未设置';
        return `
            <div class="ctx-used-detail">
                <div class="ctx-used-summary">
                    <span class="ctx-used-summary-item">模型：${this.escapeHtml(modelLabel)}</span>
                    <span class="ctx-used-summary-item">待发附件：${attachCount} 个</span>
                    <span class="ctx-used-summary-item">${summaryState}</span>
                    <span class="ctx-used-summary-item ctx-used-est" title="估算值，与 Provider 返回的实际 Token 用量分开标注">记忆部分 ≈${data.estimatedTokens || 0} tokens（估算）</span>
                </div>
                ${topicsHtml}
                ${listHtml}
                <p class="form-hint">纠正或删除仅影响后续轮次；已发送的上下文与历史转录不会被追溯抹除。</p>
            </div>`;
    }

    /** 绑定展开区条目的纠正/删除事件（仅影响后续轮次）。 */
    bindContextUsedActions() {
        const bar = document.getElementById('contextUsedBar');
        if (!bar) return;
        const data = this._lastContextUsed;
        if (!data) return;
        bar.querySelectorAll('.ctx-used-item').forEach(item => {
            const id = item.dataset.memId;
            const entry = (data.entries || []).find(x => String(x.id) === String(id));
            if (!entry) return;
            const correctBtn = item.querySelector('[data-act="correct"]');
            const forgetBtn = item.querySelector('[data-act="forget"]');
            if (correctBtn) correctBtn.addEventListener('click', () => {
                this.showMemoryForm({
                    id: entry.id,
                    content: entry.content,
                    importance: entry.importance != null ? entry.importance : 0.5,
                    tags: Array.isArray(entry.tags) ? entry.tags : [],
                    scope: entry.scope,
                    source: entry.source,
                    sourceSessionKey: ''
                });
            });
            if (forgetBtn) forgetBtn.addEventListener('click', () => this.forgetMemoryFromChat(id));
        });
    }

    /** 聊天内删除一条记忆：确认时明示只影响后续轮次。 */
    async forgetMemoryFromChat(id) {
        if (!confirm('删除这条记忆？仅影响后续轮次，已发送的上下文不会被追溯抹除。')) return;
        try {
            const resp = await this.authFetch(`/api/memory/${encodeURIComponent(id)}`, { method: 'DELETE' });
            if (resp.ok) {
                this.showToast('记忆已删除（后续轮次生效）', 'success');
                this.loadMemoryUsed();
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('删除失败 (' + resp.status + ')'), 'error');
            }
        } catch (e) {
            this.showToast('网络错误，删除失败', 'error');
        }
    }

    /**
     * 加载当前 session 的聊天历史。
     * 连续的 assistant 消息会合并成一个气泡，避免多轮工具调用产生的碎片感。
     */
    async loadChatHistory() {
        // 加载序号守卫：切换会话后，旧请求的响应不得写入新会话 DOM
        const loadSeq = ++this._chatHistoryLoadSeq;
        // 切换/重载会话时重置本地 Artifacts 即时追踪（P3：服务端登记为准，本地仅即时提示）
        this.sessionArtifacts = [];
        this.updateArtifactsBadge();
        // P3：从服务端加载该会话的持久化成果登记（切会话/刷新均可找回）
        this.loadSessionArtifacts(loadSeq);
        // P5：切会话后刷新本轮使用记忆（含会话记忆模式状态）
        this.loadMemoryUsed();
        try {
            const response = await this.authFetch(`/api/sessions/${encodeURIComponent(this.chatSessionId)}`);
            if (loadSeq !== this._chatHistoryLoadSeq) return; // 已切走：丢弃过期响应
            if (!response.ok) return;
            
            const messages = await response.json();
            if (loadSeq !== this._chatHistoryLoadSeq) return; // 已切走：丢弃过期响应
            // P5：检测是否存在压缩摘要（本轮上下文构成摘要展示用）
            this._sessionHasSummary = (messages || []).some(m => m && m.role === 'summary');
            // 过滤出有实际内容的 user/assistant 消息
            // 注意：assistant 消息即使 content 为空，只要有 toolCallRecords 也需要保留，
            // 否则工具调用卡片会因找不到对应消息而丢失
            const visibleMessages = (messages || []).filter(msg => {
                if (msg.role === 'summary') return true; // 摘要消息始终保留
                if (msg.role !== 'user' && msg.role !== 'assistant') return false;
                const hasContent = msg.content || msg.thinking || (msg.images && msg.images.length > 0);
                const hasToolCalls = msg.role === 'assistant' && msg.toolCallRecords && msg.toolCallRecords.length > 0;
                return hasContent || hasToolCalls;
            });

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
                    // 合并：用双换行分隔，保持段落感；思考过程同样拼接保留
                    last.content = [last.content, cleanContent].filter(Boolean).join('\n\n');
                    last.thinking = [last.thinking, msg.thinking].filter(Boolean).join('\n\n');
                    // 合并后的气泡覆盖一段下标区间，搜索跳转按区间匹配才能定位到正确气泡
                    if (typeof msg.index === 'number') {
                        last.indexEnd = msg.index;
                    }
                } else {
                    mergedMessages.push({
                        role: msg.role,
                        content: cleanContent,
                        images: msg.images || [],
                        thinking: msg.thinking || '',
                        toolCallRecords: msg.toolCallRecords || [],
                        // 绝对下标由后端给出，前端不推算（见 SessionsHandler 中的说明）
                        index: typeof msg.index === 'number' ? msg.index : null,
                        indexEnd: typeof msg.index === 'number' ? msg.index : null
                    });
                }
            }
            
            const messagesDiv = document.getElementById('chatMessages');
            // 先清空旧内容：避免切换到空会话时残留上一会话的消息
            messagesDiv.innerHTML = '';
            if (mergedMessages.length === 0) {
                // 空会话：展示欢迎屏（后续运行状态检查仍需执行）
                messagesDiv.innerHTML = this.getWelcomeHtml();
                this.bindQuickPrompts();
            } else {
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
                // 工具调用步骤（含工具卡片，或无正文文本的 assistant）不挂复制/重新生成操作栏：
                // 这两个操作只对真正的文本答案有意义，挂在工具卡片下方会被误认为卡片自带功能
                const renderedEl0 = messagesDiv.lastElementChild;
                if (renderedEl0 && msg.role === 'assistant'
                    && ((msg.toolCallRecords && msg.toolCallRecords.length > 0)
                        || !(msg.content || '').trim())) {
                    const bar0 = renderedEl0.querySelector(':scope > .message-actions');
                    if (bar0) bar0.remove();
                }
                // 把绝对下标写到气泡上，供搜索结果跳转定位
                if (msg.index !== null && msg.index !== undefined) {
                    const renderedEl = messagesDiv.lastElementChild;
                    if (renderedEl) {
                        renderedEl.dataset.msgIndex = String(msg.index);
                        renderedEl.dataset.msgIndexEnd = String(msg.indexEnd ?? msg.index);
                    }
                }
                // 思考过程卡片（历史回放）：插在正文前，与流式渲染顺序一致，默认折叠
                if (msg.role === 'assistant' && msg.thinking) {
                    const thinkingMsgEl = messagesDiv.lastElementChild;
                    const thinkingContentEl = thinkingMsgEl ? thinkingMsgEl.querySelector('.message-content') : null;
                    if (thinkingContentEl) {
                        this.prependHistoryThinkingCard(thinkingContentEl, msg.thinking);
                    }
                }
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
            }
            // 历史回放完成后滚到顶部，让用户从头阅读完整会话
            messagesDiv.scrollTop = 0;

            // 若本次加载来自搜索结果点击，渲染完成后再定位（DOM 此刻才就绪）
            if (this.pendingScrollToIndex !== null && this.pendingScrollToIndex !== undefined) {
                const target = this.pendingScrollToIndex;
                this.pendingScrollToIndex = null;
                this.scrollToMessageIndex(target);
            }
            
            // 检查后端是否有任务正在运行（刷新页面后恢复运行状态）
            this.checkAndRestoreRunningState(loadSeq);
        } catch (error) {
            console.error('Failed to load chat history:', error);
        }
    }

    /**
     * 生成 P1 幂等键：优先 crypto.randomUUID，降级为时间戳 + 随机串。
     * 同一提交（含双击/断线重发）复用同一 id，服务端据此去重。
     */
    newClientRequestId() {
        if (typeof crypto !== 'undefined' && crypto.randomUUID) {
            try { return crypto.randomUUID(); } catch (e) { /* 降级 */ }
        }
        return 'cr-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    }

    /**
     * 检查后端是否有任务正在运行，如果有则恢复前端的运行状态指示。
     * 用于刷新页面后恢复运行状态，避免用户误以为任务已丢失。
     *
     * <p>P1：优先走 /api/runs 执行记录接口（含状态与待确认交互，可重建审批卡）；
     * 接口不可用（旧版本 / 未启用）时回退旧 /api/chat/status。</p>
     */
    async checkAndRestoreRunningState(loadSeq) {
        // P1 路径：执行记录 + 待确认交互
        try {
            const resp = await this.authFetch('/api/runs?sessionId=' + encodeURIComponent(this.chatSessionId));
            if (loadSeq !== undefined && loadSeq !== this._chatHistoryLoadSeq) return;
            if (resp.ok) {
                const data = await resp.json();
                if (loadSeq !== undefined && loadSeq !== this._chatHistoryLoadSeq) return;
                if (data.enabled !== false) {
                    const runs = data.runs || [];
                    const active = runs.find(r => r.running);
                    if (active) {
                        this._activeRunId = active.id;
                        this.renderRunningBanner(active.status);
                        await this.restorePendingInteractions(loadSeq);
                        this.pollTaskCompletion();
                        return;
                    }
                    // 无活动执行：终态记录（如重启后的 INTERRUPTED）给出可见提示
                    const latest = runs[0];
                    if (latest && latest.status === 'INTERRUPTED' && !this._interruptNotified) {
                        this._interruptNotified = true;
                        this.showToast('服务曾重启，上次任务已中断；可查看已保存的会话内容后重新发起。', 'info');
                    }
                }
            }
        } catch (e) {
            console.warn('Failed to query runs, falling back to legacy status:', e);
        }

        // 回退路径：旧 /api/chat/status（P1 未启用时仍可恢复基本运行态）
        try {
            const response = await this.authFetch(this.chatStatusUrl());
            // 已切走会话：丢弃过期响应，不写入新会话 DOM
            if (loadSeq !== undefined && loadSeq !== this._chatHistoryLoadSeq) return;
            if (!response.ok) return;
            const status = await response.json();
            if (loadSeq !== undefined && loadSeq !== this._chatHistoryLoadSeq) return;
            if (status.running) {
                this.renderRunningBanner('RUNNING');
                this.pollTaskCompletion();
            }
        } catch (error) {
            console.warn('Failed to check running state:', error);
        }
    }

    /**
     * 渲染运行中横幅（提取自旧 checkAndRestoreRunningState，含状态文案）。
     */
    renderRunningBanner(runStatus) {
        const sendBtn = document.getElementById('sendBtn');
        if (sendBtn) {
            sendBtn.classList.add('loading');
            sendBtn.innerHTML = '⏹';
            sendBtn.title = '点击中断任务';
        }
        // 创建一个 AbortController 以支持中断
        this.currentAbortController = new AbortController();
        const messagesDiv = document.getElementById('chatMessages');
        const banner = document.createElement('div');
        banner.className = 'running-task-banner';
        banner.id = 'runningTaskBanner';
        const statusText = runStatus === 'WAITING_USER'
            ? '任务正在等待你的确认（见下方审批/提问卡）。'
            : runStatus === 'CANCELLING'
                ? '正在停止任务，等待执行器退出…'
                : '任务正在后台运行中，连接已断开。任务完成后界面自动恢复。';
        banner.innerHTML = `<span class="running-task-icon"><span class="tool-call-spinner"></span></span><span class="running-task-text">${statusText}</span><button class="running-task-stop-btn" id="runningTaskStopBtn" title="中断后台任务">⏹ 停止任务</button>`;
        messagesDiv.appendChild(banner);
        const stopBtn = banner.querySelector('#runningTaskStopBtn');
        if (stopBtn) {
            stopBtn.addEventListener('click', () => this.stopRunningTask(stopBtn));
        }
        messagesDiv.scrollTop = messagesDiv.scrollHeight;
        // 立即拉一次进度，把状态描述换成用户看得懂的阶段
        this.fetchSessionProgress(this.chatSessionId)
            .then(progress => this.renderProgressIntoBanner(progress));
    }

    /**
     * P1：拉取当前会话的待确认交互并重建审批/提问卡（刷新恢复）。
     * 已存在的交互卡不重复渲染（按 requestId 幂等）。
     */
    async restorePendingInteractions(loadSeq) {
        try {
            const resp = await this.authFetch('/api/runs/pending?sessionId=' + encodeURIComponent(this.chatSessionId));
            if (!resp.ok) return;
            const data = await resp.json();
            if (loadSeq !== undefined && loadSeq !== this._chatHistoryLoadSeq) return;
            const messagesDiv = document.getElementById('chatMessages');
            for (const info of (data.interactions || [])) {
                if (messagesDiv.querySelector(`[data-interaction-id="${this.escapeAttr(info.requestId)}"]`)) {
                    continue; // 幂等：同一交互不重复建卡
                }
                messagesDiv.appendChild(info.type === 'APPROVAL'
                    ? this.buildRestoredApprovalCard(info)
                    : this.buildRestoredAskCard(info));
            }
            if ((data.interactions || []).length > 0) {
                messagesDiv.scrollTop = messagesDiv.scrollHeight;
            }
        } catch (e) {
            console.warn('Failed to restore pending interactions:', e);
        }
    }

    /**
     * 构造恢复的审批卡（复用 hitl-card 样式；决策仍走 submitInteraction）。
     */
    buildRestoredApprovalCard(info) {
        const card = document.createElement('div');
        card.className = 'hitl-card hitl-approval';
        card.dataset.interactionId = info.requestId;
        card.innerHTML = `
            <div class="hitl-head"><span class="hitl-icon">⚠️</span><span class="hitl-title">待审批（刷新前发起）</span></div>
            ${info.reason ? `<div class="hitl-reason">${this.escapeHtml(info.reason)}</div>` : ''}
            <pre class="hitl-command">${this.escapeHtml(info.prompt || '')}</pre>
            <div class="hitl-actions">
                <button class="btn btn-sm btn-danger" data-decision="deny">拒绝</button>
                <button class="btn btn-sm btn-primary" data-decision="approve">批准执行</button>
            </div>
            <div class="hitl-status" style="display:none;"></div>`;
        const setStatus = (text, cls) => {
            const statusEl = card.querySelector('.hitl-status');
            card.querySelectorAll('.hitl-actions button').forEach(b => { b.disabled = true; });
            statusEl.textContent = text;
            statusEl.className = 'hitl-status ' + (cls || '');
            statusEl.style.display = 'block';
        };
        card.querySelectorAll('.hitl-actions button').forEach(btn => {
            btn.addEventListener('click', async () => {
                const approved = btn.dataset.decision === 'approve';
                setStatus(approved ? '已批准，继续执行…' : '已拒绝', approved ? 'hitl-ok' : 'hitl-deny');
                const ok = await this.submitInteraction(info.requestId, approved, null);
                if (!ok) setStatus('审批已失效（可能已超时）', 'hitl-deny');
            });
        });
        return card;
    }

    /**
     * 构造恢复的提问卡（自由输入 + 选项按钮，决策走 submitInteraction）。
     */
    buildRestoredAskCard(info) {
        const card = document.createElement('div');
        card.className = 'hitl-card hitl-ask';
        card.dataset.interactionId = info.requestId;
        const options = Array.isArray(info.options) ? info.options : [];
        const inputId = 'askuser_r_' + info.requestId;
        const optionBtns = options.map((o, i) =>
            `<button class="hitl-option" data-opt="${this.escapeAttr(String(i))}">${this.escapeHtml(o)}</button>`).join('');
        card.innerHTML = `
            <div class="hitl-head"><span class="hitl-icon">❓</span><span class="hitl-title">Agent 想向你确认（刷新前发起）</span></div>
            <div class="hitl-question">${this.escapeHtml(info.prompt || '')}</div>
            ${optionBtns ? `<div class="hitl-options">${optionBtns}</div>` : ''}
            <div class="hitl-input-row">
                <input type="text" class="form-control" id="${inputId}" placeholder="输入你的回答…" autocomplete="off">
                <button class="btn btn-sm btn-primary" data-send="1">发送</button>
            </div>
            <div class="hitl-status" style="display:none;"></div>`;
        const inputEl = card.querySelector('#' + inputId);
        const setStatus = (text, cls) => {
            const statusEl = card.querySelector('.hitl-status');
            card.querySelectorAll('button, input').forEach(el => { el.disabled = true; });
            statusEl.textContent = text;
            statusEl.className = 'hitl-status ' + (cls || '');
            statusEl.style.display = 'block';
        };
        const send = async (text) => {
            if (!text || !text.trim()) return;
            setStatus('已回答：' + text, 'hitl-ok');
            const ok = await this.submitInteraction(info.requestId, true, text);
            if (!ok) setStatus('提问已失效（可能已超时）', 'hitl-deny');
        };
        card.querySelectorAll('.hitl-option').forEach(btn => {
            btn.addEventListener('click', () => send(options[Number(btn.dataset.opt)]));
        });
        card.querySelector('[data-send]').addEventListener('click', () => send(inputEl.value));
        inputEl.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); send(inputEl.value); }
        });
        return card;
    }

    /**
     * 拼接任务状态查询地址：带上当前会话，只关心本会话是否在跑。
     * 不带会话时后端返回全局状态，其他通道（如 Telegram）的任务会让输入区误锁。
     */
    chatStatusUrl() {
        return this.chatSessionId
            ? '/api/chat/status?sessionId=' + encodeURIComponent(this.chatSessionId)
            : '/api/chat/status';
    }

    /**
     * 中断后台运行中的任务：通知后端 abort 并给出按钮反馈。
     * 中断信号需等当前 LLM 调用返回才生效（最长一个读超时周期），
     * 任务结束后 pollTaskCompletion 会自动清除横幅并恢复输入区。
     */
    async stopRunningTask(btn) {
        if (btn) {
            btn.disabled = true;
            btn.textContent = '已发送中断信号…';
        }
        try {
            await this.authFetch('/api/chat/abort', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: this.chatSessionId })
            });
        } catch (e) {
            console.warn('Failed to send abort to server:', e);
            if (btn) {
                btn.disabled = false;
                btn.textContent = '⏹ 停止任务';
            }
        }
    }

    /**
     * 任务结束后恢复输入区状态（发送按钮还原 + 清空中断控制器）。
     */
    restoreChatInputAfterTask() {
        const sendBtn = document.getElementById('sendBtn');
        if (sendBtn) {
            sendBtn.classList.remove('loading');
            sendBtn.innerHTML = '➤';
            sendBtn.title = '发送消息';
        }
        this.currentAbortController = null;
    }

    /**
     * 轮询后端任务状态，任务完成后恢复按钮状态并刷新历史。
     *
     * 单次请求失败（网络抖动/临时 5xx）不终止轮询，连续失败超过上限时
     * 恢复输入区并把横幅改为明示文案，避免旧实现 !response.ok 直接
     * return 导致横幅永久卡死。
     */
    async pollTaskCompletion() {
        const pollInterval = 3000;
        const maxConsecutiveFailures = 10;
        // 会话快照：轮询针对创建时的会话；用户切走后停止操作 DOM，
        // 切回时 loadChatHistory → checkAndRestoreRunningState 会重建运行横幅
        const sessionKey = this.chatSessionId;
        const statusUrl = '/api/chat/status?sessionId=' + encodeURIComponent(sessionKey);
        let failures = 0;
        const poll = async () => {
            if (sessionKey !== this.chatSessionId) return; // 已切走：停止轮询
            try {
                const response = await this.authFetch(statusUrl);
                if (!response.ok) {
                    throw new Error('status request failed: ' + response.status);
                }
                failures = 0;
                const status = await response.json();
                if (!status.running) {
                    if (sessionKey !== this.chatSessionId) return; // 已切走：不动 DOM
                    // 任务已完成，恢复按钮状态
                    this.restoreChatInputAfterTask();
                    // 移除运行中提示
                    const banner = document.getElementById('runningTaskBanner');
                    if (banner) banner.remove();
                    // 重新加载历史以获取最新的完整回复
                    this.loadChatHistory();
                    return;
                }
                // 任务仍在跑：同步刷一次阶段，让长任务的进展可见
                this.fetchSessionProgress(sessionKey)
                    .then(progress => this.renderProgressIntoBanner(progress));
                // 继续轮询
                setTimeout(poll, pollInterval);
            } catch (error) {
                failures++;
                console.warn('Poll task status failed:', error);
                if (failures >= maxConsecutiveFailures) {
                    // 持续失败：恢复输入区可用性，横幅改为停滞提示，不再自动刷新
                    this.restoreChatInputAfterTask();
                    const banner = document.getElementById('runningTaskBanner');
                    if (banner) {
                        banner.classList.add('running-task-stalled');
                        banner.innerHTML = `<span class="running-task-text">后台任务状态查询失败，已停止自动刷新。可刷新页面重新检测。</span>`;
                    }
                    return;
                }
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
     * 历史回放时，将思考过程渲染为折叠卡片并插入到消息容器最前。
     * 复用流式渲染的 thinking-card 样式；已完成态，默认折叠，点击可展开。
     *
     * @param {HTMLElement} container - 消息正文容器（.message-content）
     * @param {string} thinking - 思考过程全文
     */
    prependHistoryThinkingCard(container, thinking) {
        const card = document.createElement('div');
        card.className = 'thinking-card';
        card.innerHTML = `
            <div class="thinking-header" onclick="this.parentElement.classList.toggle('expanded')">
                <span class="tool-call-icon">💭</span>
                <span class="thinking-name">思考过程</span>
                <span class="thinking-status">✅ 完成</span>
                <span class="tool-call-toggle">▼</span>
            </div>
            <div class="thinking-body"></div>
        `;
        card.querySelector('.thinking-body').textContent = thinking;
        container.prepend(card);
    }

    /**
     * 渲染协同过程：多 Agent 对话时间线 + 可切换的关系拓扑图。
     * 在 collaborate 工具卡片下方展示，使用不同颜色区分不同角色，支持折叠/展开。
     *
     * <p>拓扑图<b>懒渲染</b>：只在用户第一次点「拓扑图」时才构建 SVG。一份会话历史
     * 可能含多条协同记录，而每条记录的图都要算一遍布局，预先全渲染会明显拖慢历史加载。</p>
     *
     * @param {HTMLElement} container - 消息容器
     * @param {Object} detail - 协同详情 { mode, goal, participants, agentMessages, metrics, topology, ... }
     */
    appendCollaborationTimeline(container, detail) {
        const timeline = document.createElement('div');
        timeline.className = 'collaboration-timeline';

        const agentMessages = detail.agentMessages || [];
        const participants = detail.participants || [];
        const mode = detail.mode || '';
        const totalRounds = detail.totalRounds || 0;
        const topology = detail.topology;
        const hasTopology = !!(topology && Array.isArray(topology.nodes) && topology.nodes.length);

        // 为每个参与者分配颜色
        const roleColors = ['#6366f1', '#ec4899', '#f59e0b', '#10b981', '#8b5cf6', '#ef4444'];
        const roleColorMap = {};
        let colorCursor = 0;
        const colorOf = (name) => {
            if (!roleColorMap[name]) {
                roleColorMap[name] = roleColors[colorCursor++ % roleColors.length];
            }
            return roleColorMap[name];
        };
        participants.forEach(colorOf);
        // 拓扑图里会出现 participants 之外的节点（Router、工作流节点、任务），
        // 提前占好颜色，保证同一角色在时间线与拓扑图中颜色一致
        if (hasTopology) {
            topology.nodes.forEach((node) => colorOf(node.label || node.id));
        }

        // 标题栏（可折叠）
        const headerHtml = `
            <div class="collab-timeline-header">
                <span class="collab-timeline-icon">🤝</span>
                <span class="collab-timeline-title">协同过程 · ${this.escapeHtml(mode)} · ${totalRounds} 轮</span>
                <span class="collab-timeline-participants">${participants.map(p =>
                    `<span class="collab-participant-tag" style="background:${colorOf(p)}20;color:${colorOf(p)}">${this.escapeHtml(p)}</span>`
                ).join('')}</span>
                ${hasTopology ? `
                <span class="collab-view-switch">
                    <button type="button" class="collab-view-btn active" data-view="timeline">时间线</button>
                    <button type="button" class="collab-view-btn" data-view="topology">拓扑图</button>
                </span>` : ''}
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

        // 拓扑图容器（内容首次切换时才填充）
        const topoHtml = hasTopology
            ? '<div class="collab-topo-body" style="display:none"></div>'
            : '';

        timeline.innerHTML = headerHtml + messagesHtml + topoHtml;
        container.appendChild(timeline);

        if (!hasTopology) {
            // 无拓扑数据（旧记录）：保留原来的整栏点击折叠行为
            timeline.querySelector('.collab-timeline-header')
                .addEventListener('click', () => timeline.classList.toggle('collapsed'));
            return;
        }

        const header = timeline.querySelector('.collab-timeline-header');
        const timelineBody = timeline.querySelector('.collab-timeline-body');
        const topoBody = timeline.querySelector('.collab-topo-body');

        header.addEventListener('click', (e) => {
            // 视图切换按钮的点击不该顺带折叠面板
            if (e.target.closest('.collab-view-switch')) return;
            timeline.classList.toggle('collapsed');
        });

        timeline.querySelectorAll('.collab-view-btn').forEach((btn) => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const view = btn.dataset.view;
                timeline.querySelectorAll('.collab-view-btn')
                    .forEach(b => b.classList.toggle('active', b === btn));
                const showTopo = view === 'topology';
                timelineBody.style.display = showTopo ? 'none' : '';
                topoBody.style.display = showTopo ? '' : 'none';
                if (showTopo && !topoBody.dataset.rendered) {
                    topoBody.dataset.rendered = '1';
                    this.renderCollaborationTopology(topoBody, topology, roleColorMap);
                }
            });
        });
    }

    /**
     * 把协同关系拓扑渲染成手写 SVG（零外部依赖）并绑定交互。
     *
     * @param {HTMLElement} mount - 挂载容器
     * @param {Object} topology - { kind, style, nodes, edges, layers, meta }
     * @param {Object} roleColorMap - 角色名 → 颜色，与时间线共用
     */
    renderCollaborationTopology(mount, topology, roleColorMap) {
        const uid = `tc${(this._topoUid = (this._topoUid || 0) + 1)}`;
        const svg = this.buildTopologySvg(topology, roleColorMap, uid);

        mount.innerHTML = `
            <div class="collab-topo-toolbar">
                <span class="collab-topo-kind">${this.escapeHtml(this.topologyKindLabel(topology))}</span>
                ${this.buildTopologyLegend(topology)}
            </div>
            <div class="collab-topo-canvas">${svg}</div>
            <div class="collab-topo-detail" style="display:none"></div>
        `;

        this.bindTopologyInteractions(mount, topology);
    }

    /**
     * 拓扑形态的中文标题。
     */
    topologyKindLabel(topology) {
        const style = topology.style ? ` · ${topology.style}` : '';
        switch (topology.kind) {
            case 'DAG': return `工作流 DAG${style}`;
            case 'TASK_GRAPH': return `任务依赖图${style}`;
            case 'HIERARCHY': return `层级汇报图${style}`;
            default: return `角色交互图${style}`;
        }
    }

    /**
     * 图例：节点状态配色 + 规模统计。
     */
    buildTopologyLegend(topology) {
        const statuses = new Set((topology.nodes || []).map(n => n.status).filter(Boolean));
        const palette = TinyClawConsole.TOPO_STATUS_COLORS;
        const items = Object.keys(palette)
            .filter(s => statuses.has(s))
            .map(s => `<span class="collab-topo-legend-item">
                    <i style="background:${palette[s]}"></i>${s}</span>`);
        items.push(`<span class="collab-topo-legend-item collab-topo-legend-count">
                ${(topology.nodes || []).length} 节点 / ${(topology.edges || []).length} 边</span>`);
        return `<span class="collab-topo-legend">${items.join('')}</span>`;
    }

    /** 节点状态配色，与全局 CSS 变量语义保持一致 */
    static TOPO_STATUS_COLORS = {
        COMPLETED: '#10b981',
        RUNNING: '#f59e0b',
        FAILED: '#ef4444',
        SKIPPED: '#94a3b8',
        PENDING: '#cbd5e1'
    };

    /**
     * 构建拓扑 SVG 字符串。
     *
     * <p>两种布局：</p>
     * <ul>
     *   <li><b>分层布局</b>（后端给了 {@code layers}：DAG / 任务依赖 / 金字塔）——
     *       自底向上排布，同层水平均分，边用竖向三次贝塞尔曲线；</li>
     *   <li><b>环形布局</b>（讨论型关系图，无层序可言）——节点均匀分布在圆周上，
     *       边用向圆心内凹的二次贝塞尔曲线，避免弦直接穿过中心区域的其他节点。</li>
     * </ul>
     */
    buildTopologySvg(topology, roleColorMap, uid) {
        const nodes = topology.nodes || [];
        const edges = topology.edges || [];
        const NODE_W = 138;
        const NODE_H = 40;
        const pos = new Map();
        let width;
        let height;
        let center = null;

        if (Array.isArray(topology.layers) && topology.layers.length) {
            const X_GAP = 178;
            const Y_GAP = 98;
            const PAD_X = 48;
            const PAD_Y = 44;

            // 理论上 layers 已覆盖全部节点；仍有遗漏时补一行到顶部，
            // 让悬空节点可见而不是被悄悄丢弃
            const layers = topology.layers.map(layer => Array.isArray(layer) ? layer.slice() : []);
            const placed = new Set(layers.flat());
            const orphans = nodes.map(n => n.id).filter(id => !placed.has(id));
            if (orphans.length) layers.push(orphans);

            const widest = layers.reduce((max, layer) => Math.max(max, layer.length), 1);
            width = Math.max(widest * X_GAP, 380) + PAD_X * 2;
            height = layers.length * Y_GAP + PAD_Y * 2;

            layers.forEach((layer, layerIndex) => {
                // layerIndex 0 是最底层，画在最下面（金字塔/DAG 都是自下往上读）
                const y = height - PAD_Y - NODE_H / 2 - layerIndex * Y_GAP;
                const startX = (width - layer.length * X_GAP) / 2 + X_GAP / 2;
                layer.forEach((id, i) => pos.set(id, { x: startX + i * X_GAP, y }));
            });
        } else {
            const count = nodes.length;
            const radius = Math.max(120, count * 46);
            width = radius * 2 + NODE_W + 90;
            height = radius * 2 + NODE_H + 90;
            center = { x: width / 2, y: height / 2 };
            nodes.forEach((node, i) => {
                const angle = (i / count) * Math.PI * 2 - Math.PI / 2;
                pos.set(node.id, {
                    x: center.x + radius * Math.cos(angle),
                    y: center.y + radius * Math.sin(angle)
                });
            });
        }

        const maxWeight = edges.reduce((max, e) => Math.max(max, e.weight || 1), 1);
        const edgeSvg = edges.map((edge) => {
            const s = pos.get(edge.from);
            const t = pos.get(edge.to);
            if (!s || !t) return '';
            const weight = edge.weight || 1;
            // 权重映射到线宽与不透明度：互动最密集的一对角色一眼可见
            const strokeW = 1.2 + Math.min(3, (weight / maxWeight) * 2.6);
            const opacity = 0.38 + Math.min(0.5, (weight / maxWeight) * 0.5);
            const d = center
                ? this.topoCurvedEdge(s, t, center, NODE_W, NODE_H)
                : this.topoLayeredEdge(s, t, NODE_W, NODE_H);
            return `<path class="topo-edge" d="${d}" data-from="${this.escapeAttr(edge.from)}"
                    data-to="${this.escapeAttr(edge.to)}"
                    stroke-width="${strokeW.toFixed(2)}" opacity="${opacity.toFixed(2)}"></path>`;
        }).join('');

        const nodeSvg = nodes.map((node, index) => {
            const p = pos.get(node.id);
            if (!p) return '';
            const color = roleColorMap[node.label] || roleColorMap[node.id] || '#6366f1';
            const status = node.status || 'PENDING';
            const statusColor = TinyClawConsole.TOPO_STATUS_COLORS[status] || '#cbd5e1';
            const label = this.truncateTopoLabel(node.label || node.id, 9);
            const type = node.type && node.type !== 'AGENT' ? node.type : '';
            const agents = Array.isArray(node.agents) && node.agents.length
                ? `\n参与: ${node.agents.join(', ')}` : '';
            return `<g class="topo-node" data-idx="${index}"
                    transform="translate(${(p.x - NODE_W / 2).toFixed(1)}, ${(p.y - NODE_H / 2).toFixed(1)})">
                <title>${this.escapeHtml(`${node.label || node.id}${type ? ' [' + type + ']' : ''} · ${status}${agents}`)}</title>
                ${type ? `<text class="topo-node-type" x="${NODE_W / 2}" y="-7">${this.escapeHtml(type)}</text>` : ''}
                <rect class="topo-node-box" width="${NODE_W}" height="${NODE_H}" rx="10"
                      style="stroke:${color}"></rect>
                <rect class="topo-node-accent" width="4" height="${NODE_H}" rx="2"
                      style="fill:${color}"></rect>
                <circle class="topo-node-status" cx="16" cy="${NODE_H / 2}" r="4.5"
                        style="fill:${statusColor}"></circle>
                <text class="topo-node-label" x="${NODE_W / 2 + 8}" y="${NODE_H / 2 + 1}">${this.escapeHtml(label)}</text>
            </g>`;
        }).join('');

        return `<svg class="topo-svg" viewBox="0 0 ${width.toFixed(0)} ${height.toFixed(0)}"
                preserveAspectRatio="xMidYMid meet" role="img">
            <defs>
                <marker id="${uid}a" viewBox="0 0 10 10" refX="9" refY="5"
                        markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                    <path d="M 0 1 L 9 5 L 0 9 z" fill="#94a3b8"></path>
                </marker>
            </defs>
            <g class="topo-edges" marker-end="url(#${uid}a)">${edgeSvg}</g>
            <g class="topo-nodes">${nodeSvg}</g>
        </svg>`;
    }

    /**
     * 分层布局的边：从源节点顶边到目标节点底边的竖向三次贝塞尔。
     * 同层或层序颠倒时改走侧边，避免画出穿过节点框的竖线。
     */
    topoLayeredEdge(s, t, nodeW, nodeH) {
        if (Math.abs(s.y - t.y) < nodeH) {
            const leftToRight = t.x > s.x;
            const sx = s.x + (leftToRight ? nodeW / 2 : -nodeW / 2);
            const tx = t.x + (leftToRight ? -nodeW / 2 : nodeW / 2);
            const bow = (sx + tx) / 2;
            const lift = Math.min(34, Math.abs(tx - sx) * 0.28) + nodeH * 0.6;
            return `M ${sx.toFixed(1)} ${s.y.toFixed(1)} C ${bow.toFixed(1)} ${(s.y - lift).toFixed(1)},`
                + ` ${bow.toFixed(1)} ${(t.y - lift).toFixed(1)}, ${tx.toFixed(1)} ${t.y.toFixed(1)}`;
        }
        const upward = t.y < s.y;
        const sx = s.x;
        const sy = s.y + (upward ? -nodeH / 2 : nodeH / 2);
        const tx = t.x;
        const ty = t.y + (upward ? nodeH / 2 : -nodeH / 2);
        const midY = (sy + ty) / 2;
        return `M ${sx.toFixed(1)} ${sy.toFixed(1)} C ${sx.toFixed(1)} ${midY.toFixed(1)},`
            + ` ${tx.toFixed(1)} ${midY.toFixed(1)}, ${tx.toFixed(1)} ${ty.toFixed(1)}`;
    }

    /**
     * 环形布局的边：两端裁到节点矩形边界，控制点向圆心内凹形成弧线。
     */
    topoCurvedEdge(s, t, center, nodeW, nodeH) {
        const start = this.topoRectBoundary(s, t, nodeW, nodeH);
        const end = this.topoRectBoundary(t, s, nodeW, nodeH);
        const mx = (start.x + end.x) / 2;
        const my = (start.y + end.y) / 2;
        const ctrlX = mx + (center.x - mx) * 0.42;
        const ctrlY = my + (center.y - my) * 0.42;
        return `M ${start.x.toFixed(1)} ${start.y.toFixed(1)} Q ${ctrlX.toFixed(1)} ${ctrlY.toFixed(1)},`
            + ` ${end.x.toFixed(1)} ${end.y.toFixed(1)}`;
    }

    /**
     * 求从矩形中心 {@code box} 指向 {@code toward} 的射线与矩形边界的交点。
     * 不裁剪的话箭头会落在节点框底下，看不见指向。
     */
    topoRectBoundary(box, toward, nodeW, nodeH) {
        const dx = toward.x - box.x;
        const dy = toward.y - box.y;
        if (dx === 0 && dy === 0) return { x: box.x, y: box.y };
        const hw = nodeW / 2;
        const hh = nodeH / 2;
        const scaleX = dx === 0 ? Infinity : hw / Math.abs(dx);
        const scaleY = dy === 0 ? Infinity : hh / Math.abs(dy);
        const scale = Math.min(scaleX, scaleY);
        return { x: box.x + dx * scale, y: box.y + dy * scale };
    }

    /**
     * 按显示宽度截断节点标签。
     *
     * <p>SVG 的 {@code text} 不支持 text-overflow，只能自己截。CJK 字符宽度约等于
     * 字号，拉丁字符约为其 0.55 倍，因此按加权单位数而非字符数截断。</p>
     */
    truncateTopoLabel(text, maxUnits) {
        if (!text) return '';
        let units = 0;
        let out = '';
        for (const ch of String(text)) {
            units += /[\u2e80-\u9fff\uff00-\uffef]/.test(ch) ? 1 : 0.55;
            if (units > maxUnits) return out + '…';
            out += ch;
        }
        return out;
    }

    /**
     * 绑定拓扑图交互：悬停高亮相邻关系、点击查看详情。
     *
     * <p>高亮用「加 class + CSS 降透明度」而不是逐个改内联样式：一次协同可能有
     * 上百条边，逐个写 style 会触发布局抖动，而切换根节点的一个 class 只需一次重绘。</p>
     */
    bindTopologyInteractions(mount, topology) {
        const svg = mount.querySelector('.topo-svg');
        const detailBox = mount.querySelector('.collab-topo-detail');
        if (!svg) return;

        const nodeEls = Array.from(svg.querySelectorAll('.topo-node'));
        const edgeEls = Array.from(svg.querySelectorAll('.topo-edge'));
        const nodes = topology.nodes || [];

        nodeEls.forEach((el) => {
            const node = nodes[Number(el.dataset.idx)];
            if (!node) return;

            el.addEventListener('mouseenter', () => {
                svg.classList.add('topo-focus');
                el.classList.add('hl');
                const linked = new Set([node.id]);
                edgeEls.forEach((edge) => {
                    const incident = edge.dataset.from === node.id || edge.dataset.to === node.id;
                    edge.classList.toggle('hl', incident);
                    if (incident) {
                        linked.add(edge.dataset.from);
                        linked.add(edge.dataset.to);
                    }
                });
                nodeEls.forEach((other) => {
                    const otherNode = nodes[Number(other.dataset.idx)];
                    other.classList.toggle('linked', !!otherNode && linked.has(otherNode.id));
                });
            });

            el.addEventListener('mouseleave', () => {
                svg.classList.remove('topo-focus');
                el.classList.remove('hl');
                edgeEls.forEach(edge => edge.classList.remove('hl'));
                nodeEls.forEach(other => other.classList.remove('linked'));
            });

            el.addEventListener('click', () => {
                nodeEls.forEach(other => other.classList.remove('selected'));
                el.classList.add('selected');
                const inbound = edgeEls.filter(e => e.dataset.to === node.id).length;
                const outbound = edgeEls.filter(e => e.dataset.from === node.id).length;
                detailBox.style.display = '';
                detailBox.innerHTML = `
                    <div class="collab-topo-detail-head">
                        <strong>${this.escapeHtml(node.label || node.id)}</strong>
                        ${node.type ? `<span class="collab-topo-detail-type">${this.escapeHtml(node.type)}</span>` : ''}
                        <span class="collab-topo-detail-status" data-status="${this.escapeAttr(node.status || 'PENDING')}">${this.escapeHtml(node.status || 'PENDING')}</span>
                        <span class="collab-topo-detail-degree">入 ${inbound} / 出 ${outbound}</span>
                    </div>
                    ${node.detail ? `<pre class="collab-topo-detail-body">${this.escapeHtml(node.detail)}</pre>`
                        : '<div class="collab-topo-detail-empty">该节点没有更多详情</div>'}
                `;
            });
        });
    }

    /**
     * 待定位的消息下标：搜索结果点击后先记下来，等历史渲染完再用
     */
    pendingScrollToIndex = null;

    /** 搜索输入防抖定时器 */
    searchDebounceTimer = null;

    // ==================== 会话搜索 ====================

    /**
     * 绑定侧栏搜索框。
     *
     * <p>用 300ms 防抖而不是逐字请求：后端搜索是扫转录文件，逐字发会把一次输入
     * 变成十几次全目录扫描。</p>
     */
    bindChatSearch() {
        const input = document.getElementById('chatSearchInput');
        const clearBtn = document.getElementById('chatSearchClear');
        if (!input) return;

        input.addEventListener('input', () => {
            const query = input.value.trim();
            clearBtn.style.display = query ? 'block' : 'none';
            clearTimeout(this.searchDebounceTimer);
            if (!query) {
                this.exitSearchMode();
                return;
            }
            this.searchDebounceTimer = setTimeout(() => this.searchMessages(query), 300);
        });

        input.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                input.value = '';
                clearBtn.style.display = 'none';
                this.exitSearchMode();
            }
        });

        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                input.value = '';
                clearBtn.style.display = 'none';
                this.exitSearchMode();
                input.focus();
            });
        }
    }

    /**
     * 执行搜索并用结果列表替掉历史列表
     */
    async searchMessages(query) {
        const resultsDiv = document.getElementById('chatSearchResults');
        const historyDiv = document.getElementById('chatHistory');
        if (!resultsDiv || !historyDiv) return;

        resultsDiv.style.display = 'block';
        historyDiv.style.display = 'none';
        resultsDiv.innerHTML = '<div class="chat-search-hint">搜索中…</div>';

        try {
            const response = await this.authFetch(
                `/api/sessions/search?q=${encodeURIComponent(query)}&limit=30`);
            if (!response.ok) {
                resultsDiv.innerHTML = '<div class="chat-search-hint">搜索失败</div>';
                return;
            }
            const hits = await response.json();
            this.renderSearchResults(hits, query);
        } catch (error) {
            console.error('Search failed:', error);
            resultsDiv.innerHTML = '<div class="chat-search-hint">搜索失败</div>';
        }
    }

    renderSearchResults(hits, query) {
        const resultsDiv = document.getElementById('chatSearchResults');
        if (!hits || hits.length === 0) {
            resultsDiv.innerHTML = '<div class="chat-search-hint">无匹配结果</div>';
            return;
        }

        const header = `<div class="chat-search-count">${hits.length} 条匹配</div>`;
        const items = hits.map(hit => {
            const title = this.extractChatTitle(hit.sessionKey, hit.title);
            const roleLabel = hit.role === 'user' ? '我' : hit.role === 'assistant' ? 'AI' : hit.role;
            return `
                <div class="chat-search-item" data-session="${this.escapeHtml(hit.sessionKey)}"
                     data-index="${hit.messageIndex}">
                    <div class="chat-search-item-head">
                        <span class="chat-search-role chat-search-role-${this.escapeHtml(hit.role)}">${this.escapeHtml(roleLabel)}</span>
                        <span class="chat-search-session">${this.escapeHtml(title)}</span>
                    </div>
                    <div class="chat-search-snippet">${this.highlightQuery(hit.snippet, query)}</div>
                </div>`;
        }).join('');

        resultsDiv.innerHTML = header + items;
        resultsDiv.querySelectorAll('.chat-search-item').forEach(item => {
            item.addEventListener('click', () => {
                this.openSearchHit(item.dataset.session, parseInt(item.dataset.index, 10));
            });
        });
    }

    /**
     * 在片段里高亮匹配词。
     *
     * <p>先 escape 再插标签，顶上去看似多余——但反过来先插标签再 escape 会把
     * 高亮标签也转义成文本，而不 escape 则把会话内容直接当 HTML 执行。</p>
     */
    highlightQuery(snippet, query) {
        const safe = this.escapeHtml(snippet || '');
        if (!query) return safe;
        const safeQuery = this.escapeHtml(query);
        // 转义正则元字符，否则用户输入的 ( 或 [ 会让 RegExp 抛异常
        const escaped = safeQuery.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        try {
            return safe.replace(new RegExp(escaped, 'gi'), m => `<mark>${m}</mark>`);
        } catch (e) {
            return safe;
        }
    }

    /**
     * 打开搜索命中：切会话并定位到那条消息。
     *
     * <p>定位不在这里做：loadChatHistory 是异步的，此刻 DOM 还是旧会话的。
     * 把目标下标存起来，由渲染完成处回调。</p>
     */
    openSearchHit(sessionKey, messageIndex) {
        this.pendingScrollToIndex = Number.isInteger(messageIndex) ? messageIndex : null;
        if (sessionKey === this.chatSessionId) {
            // 同一会话：不重新加载，直接定位
            const target = this.pendingScrollToIndex;
            this.pendingScrollToIndex = null;
            this.scrollToMessageIndex(target);
            return;
        }
        this.switchChatSession(sessionKey);
    }

    /**
     * 滚动到指定绝对下标的消息并瞬时高亮。
     *
     * <p>按区间匹配：连续的 assistant 消息会被合并成一个气泡，它覆盖
     * [msgIndex, msgIndexEnd] 这段下标；只比较起点会在命中合并的后半段时找不到。</p>
     */
    scrollToMessageIndex(messageIndex) {
        if (messageIndex === null || messageIndex === undefined) return;
        const messagesDiv = document.getElementById('chatMessages');
        if (!messagesDiv) return;

        const target = [...messagesDiv.querySelectorAll('[data-msg-index]')].find(el => {
            const start = parseInt(el.dataset.msgIndex, 10);
            const end = parseInt(el.dataset.msgIndexEnd ?? el.dataset.msgIndex, 10);
            return messageIndex >= start && messageIndex <= end;
        });
        if (!target) return;

        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('message-search-hit');
        setTimeout(() => target.classList.remove('message-search-hit'), 2400);
    }

    /**
     * 退出搜索模式，恢复历史列表
     */
    exitSearchMode() {
        const resultsDiv = document.getElementById('chatSearchResults');
        const historyDiv = document.getElementById('chatHistory');
        if (resultsDiv) {
            resultsDiv.style.display = 'none';
            resultsDiv.innerHTML = '';
        }
        if (historyDiv) {
            historyDiv.style.display = '';
        }
    }

    // ==================== 进度卡 ====================

    /**
     * 拉取会话进度卡，无进行中任务时返回 null。
     */
    async fetchSessionProgress(sessionKey) {
        if (!sessionKey) return null;
        try {
            const response = await this.authFetch(
                `/api/sessions/${encodeURIComponent(sessionKey)}/progress`);
            if (!response.ok) return null;
            const data = await response.json();
            return data.running ? data.progress : null;
        } catch (error) {
            // 进度是锥上添花，拉不到就保持原有文案，不影响任务轮询
            return null;
        }
    }

    /**
     * 把进度卡渲染进运行中横幅。
     *
     * <p>只改文案部分，不重建整个横幅：重建会把已绑定事件的停止按钮一起换掉，
     * 用户就再也停不了任务了。</p>
     */
    renderProgressIntoBanner(progress) {
        const banner = document.getElementById('runningTaskBanner');
        if (!banner) return;
        const textEl = banner.querySelector('.running-task-text');
        if (!textEl) return;

        if (!progress || !progress.phase) {
            textEl.textContent = '有任务正在后台运行中。任务完成后界面自动恢复。';
            return;
        }

        const stepText = progress.hasKnownTotal
            ? ` (${progress.completedSteps}/${progress.totalSteps})`
            : '';
        const detail = progress.detail
            ? `<span class="running-task-detail">${this.escapeHtml(progress.detail)}</span>`
            : '';
        textEl.innerHTML =
            `<span class="running-task-phase">${this.escapeHtml(progress.phase)}${stepText}</span>${detail}`;
    }

    /**
     * 加载左侧历史聊天会话列表，按天分组折叠显示。
     * P4：置顶优先、归档默认隐藏（可切换显示）、自定义标题覆盖摘要标题。
     */
    async loadChatSessions() {
        try {
            const response = await this.authFetch('/api/sessions');
            const sessions = await response.json();
            
            // P5：缓存当前会话记忆模式（本轮上下文条与切换入口展示用）
            const currentSession = sessions.find(s => s.key === this.chatSessionId);
            const newMemoryMode = currentSession
                ? (currentSession.memoryMode || 'DEFAULT') : 'DEFAULT';
            if (newMemoryMode !== this._sessionMemoryMode) {
                this._sessionMemoryMode = newMemoryMode;
                // 模式变化（如别处切换后刷新列表）时同步重渲染本轮上下文条
                if (this._lastContextUsed) this.renderContextUsedBar();
            }
            
            // 只显示 web: 开头的会话；置顶 > 时间戳降序；归档默认隐藏（开关见侧栏底部）
            const showArchived = this._showArchivedSessions === true;
            const webSessions = sessions
                .filter(s => s.key.startsWith('web:'))
                .filter(s => showArchived || !s.archived)
                .sort((a, b) => {
                    if (!!b.pinned !== !!a.pinned) return b.pinned ? 1 : -1; // 置顶优先
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
                                // P4：自定义标题 > localStorage 缓存首条 > 摘要标题
                                const title = s.displayTitle || this.extractChatTitle(s.key, s.firstMessage);
                                // 后端在会话索引里带上了进度，侧栏据此直接标出“哪个会话在跑”
                                const runningBadge = s.progress
                                    ? `<span class="history-running" title="${this.escapeHtml(s.progress.phase || '运行中')}"></span>`
                                    : '';
                                const sharedBadge = s.visibility === 'SHARED'
                                    ? '<span class="history-shared" title="共享会话">◍</span>'
                                    : '';
                                const pinnedBadge = s.pinned
                                    ? '<span class="history-pinned" title="已置顶">📌</span>'
                                    : '';
                                const archivedBadge = s.archived
                                    ? '<span class="history-archived" title="已归档">🗄</span>'
                                    : '';
                                return `
                                    <div class="chat-history-item ${isActive ? 'active' : ''}${s.archived ? ' item-archived' : ''}" data-session="${this.escapeHtml(s.key)}">
                                        ${pinnedBadge}${runningBadge}
                                        <span class="history-title">${this.escapeHtml(title)}</span>
                                        ${archivedBadge}${sharedBadge}
                                        <button class="history-menu" onclick="event.stopPropagation(); app.showSessionMenu('${this.escapeHtml(s.key)}', event)" title="整理选项">⋯</button>
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

            // 绑定点击与键盘事件（移动端抽屉内支持 Enter 键导航）
            historyDiv.querySelectorAll('.chat-history-item').forEach(item => {
                item.setAttribute('tabindex', '0');
                item.addEventListener('click', () => {
                    const sessionKey = item.dataset.session;
                    this.switchChatSession(sessionKey);
                });
                item.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        this.switchChatSession(item.dataset.session);
                    }
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
     * 把输入框当前正文保存为当前会话草稿（切走前调用，两会话草稿互不串写）。
     */
    saveCurrentInputAsDraft() {
        const input = document.getElementById('chatInput');
        if (input) this.saveDraft(input.value);
    }

    /**
     * 切换到指定聊天会话
     */
    switchChatSession(sessionKey) {
        if (sessionKey === this.chatSessionId) return;
        // 先保存旧会话草稿再切换，待上传图片/附件不跨会话残留
        this.saveCurrentInputAsDraft();
        this.chatSessionId = sessionKey;
        localStorage.setItem('tinyclaw_chat_session', this.chatSessionId);
        this.clearPendingImages();
        this.clearPendingAttachments();
        this.restoreDraft();
        this.loadChatHistory();
        this.loadChatSessions();
        // 小屏抽屉中选中会话后自动收起
        this.closeChatDrawer();
    }

    /**
     * 删除聊天会话
     */
    async deleteChatSession(key) {
        if (!confirm('Delete this chat?')) return;
        try {
            await this.authFetch(`/api/sessions/${encodeURIComponent(key)}`, { method: 'DELETE' });
            // 清除该会话缓存的标题与草稿
            localStorage.removeItem(`tinyclaw_title_${key}`);
            try { localStorage.removeItem('tinyclaw_draft:' + key); } catch (e) { /* 忽略 */ }
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

    // ==================== P4：会话整理（标题/置顶/归档） ====================

    /** 归档可见开关（默认隐藏归档会话）。 */
    _showArchivedSessions = false;

    /**
     * 会话整理菜单（重命名 / 置顶 / 归档 / 删除 / 记忆模式）：原生 confirm/prompt 保持零依赖。
     */
    async showSessionMenu(key) {
        const memoryOff = (this._sessionMemoryMode === 'OFF' && key === this.chatSessionId);
        const choice = prompt(`会话整理——输入序号：\n1 重命名\n2 置顶/取消置顶\n3 归档/取消归档\n4 查看/隐藏归档会话\n5 ${memoryOff ? '恢复使用长期记忆' : '不使用长期记忆（聊天历史仍会保存）'}\n6 设置/清除项目归属\n\n（删除请用条目右侧 ×）`, '');
        if (choice === null) return;
        const c = choice.trim();
        if (c === '1') {
            this.renameChatSession(key);
        } else if (c === '2') {
            await this.toggleSessionFlag(key, 'pinned');
        } else if (c === '3') {
            await this.toggleSessionFlag(key, 'archived');
        } else if (c === '4') {
            this._showArchivedSessions = !this._showArchivedSessions;
            this.loadChatSessions();
            this.showToast(this._showArchivedSessions ? '显示归档会话' : '隐藏归档会话', 'info');
        } else if (c === '5') {
            await this.toggleSessionMemoryMode(key);
        } else if (c === '6') {
            await this.assignSessionProject(key);
        } else {
            this.showToast('请输入 1-6 的序号', 'info');
        }
    }

    /**
     * P4：设置/清除会话的项目归属。一旦产生消息，归属固定（迁移需 fork 新会话）；
     * 归属后项目指令与项目记忆域随会话注入。
     */
    async assignSessionProject(key) {
        let projects = [];
        try {
            const resp = await this.authFetch('/api/projects');
            if (resp.ok) {
                const data = await resp.json();
                projects = data.projects || [];
            }
        } catch (e) { /* 项目能力未启用时走空列表，仅支持清除归属 */ }
        if (!projects.length) {
            const clear = confirm('尚无可选项目（或项目能力未启用）。是否清除当前归属？');
            if (clear) await this.applySessionProject(key, '');
            return;
        }
        const menu = projects.map((p, i) => `${i + 1} ${p.name}${p.archived ? '（已归档）' : ''}`).join('\n');
        const input = prompt(`设置项目归属（仅无消息会话可设置；产生消息后固定，迁移需 fork）：\n\n${menu}\n0 清除归属\n\n输入序号：`, '');
        if (input === null) return;
        const idx = parseInt(input.trim(), 10);
        if (isNaN(idx)) { this.showToast('请输入序号', 'info'); return; }
        if (idx === 0) {
            await this.applySessionProject(key, '');
        } else if (idx >= 1 && idx <= projects.length) {
            await this.applySessionProject(key, projects[idx - 1].id);
        } else {
            this.showToast('序号超出范围', 'info');
        }
    }

    /** 应用项目归属到服务端（PATCH /flags 的 projectId 字段）。 */
    async applySessionProject(key, projectId) {
        try {
            const resp = await this.authFetch(`/api/sessions/${encodeURIComponent(key)}/flags`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ projectId: projectId || null })
            });
            if (resp.ok) {
                this.showToast(projectId ? '项目归属已设置' : '项目归属已清除', 'success');
                this.loadChatSessions();
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('设置失败 (' + resp.status + ')'), 'error');
            }
        } catch (e) {
            this.showToast('网络错误，设置失败', 'error');
        }
    }

    /**
     * P5：切换会话记忆模式（DEFAULT/OFF）。
     * OFF 同时关闭该会话长期记忆自动检索与自动提取写入，仅影响后续轮次；
     * 聊天历史仍正常保存（不使用隐身类命名）。手动在记忆管理页新增不受影响。
     */
    async toggleSessionMemoryMode(key) {
        const target = this._sessionMemoryMode === 'OFF' ? 'DEFAULT' : 'OFF';
        try {
            const resp = await this.authFetch(`/api/sessions/${encodeURIComponent(key)}/flags`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ memoryMode: target })
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                throw new Error(err.error || ('HTTP ' + resp.status));
            }
            this._sessionMemoryMode = target;
            if (key === this.chatSessionId) {
                this.renderContextUsedBar();
            }
            this.loadChatSessions();
            this.showToast(target === 'OFF'
                ? '本会话不再使用长期记忆（仅后续轮次生效，聊天历史仍保存）'
                : '本会话已恢复使用长期记忆', 'success');
        } catch (e) {
            this.showToast('切换记忆模式失败：' + e.message, 'error');
        }
    }

    /**
     * 重命名会话：标题写入服务端（可重建，非仅 localStorage）；空输入回退默认标题。
     */
    async renameChatSession(key) {
        const title = prompt('新的会话标题（留空恢复默认标题）：', '');
        if (title === null) return; // 取消
        try {
            const resp = await this.authFetch(`/api/sessions/${encodeURIComponent(key)}/flags`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ displayTitle: title })
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                throw new Error(err.error || ('HTTP ' + resp.status));
            }
            this.loadChatSessions();
            this.showToast(title.trim() ? '已重命名' : '已恢复默认标题', 'success');
        } catch (e) {
            this.showToast('重命名失败：' + e.message, 'error');
        }
    }

    /**
     * 切换置顶/归档标记。
     * @param {string} key 会话 key
     * @param {'pinned'|'archived'} flag 目标标记
     */
    async toggleSessionFlag(key, flag) {
        try {
            const listResp = await this.authFetch('/api/sessions');
            const sessions = listResp.ok ? await listResp.json() : [];
            const current = sessions.find(s => s.key === key);
            const nextValue = !((current && current[flag]) === true);
            const resp = await this.authFetch(`/api/sessions/${encodeURIComponent(key)}/flags`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ [flag]: nextValue })
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                throw new Error(err.error || ('HTTP ' + resp.status));
            }
            this.loadChatSessions();
            const labels = { pinned: '置顶', archived: '归档' };
            this.showToast(nextValue ? `已${labels[flag]}` : `已取消${labels[flag]}`, 'success');
        } catch (e) {
            this.showToast('操作失败：' + e.message, 'error');
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
        // P6：记录本轮用户消息（「设为周期任务」表单预填任务描述）
        if (message) {
            this._lastUserMessage = message;
        }
        const hasImages = this.pendingImages.length > 0;
        // P2：就绪附件随消息发送；存在未就绪附件时提示（不阻断，由用户决定）
        const attachmentIds = this.readyAttachmentIds();
        const notReady = this.pendingAttachments.filter(a => a.status !== 'READY').length;
        
        if (!message && !hasImages && attachmentIds.length === 0) return;
        if (notReady > 0) {
            this.showToast(`${notReady} 个附件尚未就绪，本次将不包含它们`, 'info');
        }

        // 发送前先保存草稿：仅服务端确认接收后清空，失败时用于恢复输入
        this.saveDraft(message);

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
                if (!uploadResp.ok) throw new Error('HTTP ' + uploadResp.status);
                const uploadResult = await uploadResp.json();
                imagePaths = uploadResult.files || [];
                if (imagePaths.length === 0) throw new Error('no files returned');
            } catch (err) {
                console.error('Failed to upload images:', err);
                // 上传失败：中止本次发送，恢复输入与待上传图片，不产生半成品消息
                this.currentAbortController = null;
                sendBtn.classList.remove('loading');
                sendBtn.disabled = false;
                sendBtn.textContent = '↑';
                sendBtn.title = '';
                this.restoreFailedInput(message);
                this.showToast('图片上传失败：' + err.message + '，消息未发送', 'error');
                return;
            }
        }

        // fork 重新生成：原始提问携带的图片已是服务器路径，直接复用，跳过 base64 上传
        if (this._replayImagePaths && this._replayImagePaths.length) {
            imagePaths = this._replayImagePaths;
            this._replayImagePaths = null;
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
        // P2：附件已随请求发送，清空待发送列表
        this.clearPendingAttachments();

        // Add assistant message placeholder for streaming
        const assistantDiv = document.createElement('div');
        assistantDiv.className = 'message assistant';
        assistantDiv.innerHTML = '<div class="message-content"></div>';
        messagesDiv.appendChild(assistantDiv);
        // 用户上滑阅读时新增占位气泡不强制拉底，新内容由流循环的徽标提示
        if (this.isNearBottom(messagesDiv)) {
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }

        const contentDiv = assistantDiv.querySelector('.message-content');

        // 渲染状态：跟踪当前正在流式输出的文本内容 div 和工具调用卡片
        let currentTextContent = '';
        let currentTextDiv = null;
        // 本轮 assistant 正文的完整原始文本（跨多个 text div 累加，不因分段而重置），供“复制”使用
        let assistantRawText = '';
        // 当前任务计划卡片（PLAN 事件就地刷新同一张卡，而非每次新建）
        let currentPlanCard = null;
        // toolCardMap: 归属前缀+toolName -> { card, statusEl, resultSectionEl, resultEl }
        const toolCardMap = {};
        // subagentCardMap: taskId -> { card, bodyEl, statusEl, contentBuffer }
        const subagentCardMap = {};
        // 当前思维链折叠卡片（THINKING 事件流式追加目标）
        let currentThinkingDiv = null;
        let currentThinkingContent = '';
        // 本轮已固化的思考卡片：迟到的 THINKING 事件并回这里，而不是在正文后新建卡片。
        // 工具调用是轮次边界，跨过它之后的思考属于新的推理阶段，需另开卡片，故在 TOOL_START 清空。
        let lastRoundThinkingDiv = null;
        // 当前协同 Agent 的发言块索引：turn -> { agent, block, contentEl, contentBuffer, thinkingDiv, thinkingBuffer }
        // 按发言轮次（turn）而非「当前块」建索引：并行协同时多个 Agent 的事件交错到达，
        // 只认「当前块」会把同一次发言拆成多块；顺序型多轮发言则因 turn 不同天然分块
        const collabBlockMap = {};
        // 实时协同拓扑：{ wrap, bodyEl, progressEl, colorMap, colorCursor, topology }
        // 协同开始时由 COLLABORATE_TOPOLOGY 事件创建，节点状态靠 COLLABORATE_NODE 增量点亮，
        // 终版拓扑到达后全量替换；协同结束/流结束释放引用（DOM 保留终版图）
        let liveCollabTopo = null;
        // 协同角色配色：与 appendCollaborationTimeline 共用同一套，
        // 保证实时图与刷新后历史里的图颜色一致
        const collabRoleColors = ['#6366f1', '#ec4899', '#f59e0b', '#10b981', '#8b5cf6', '#ef4444'];

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
         * 结束当前思维链卡片：标记完成、移除流式光标并折叠。
         * 思考结束（正文开始、工具调用或流结束）时调用。
         */
        const finalizeThinking = () => {
            if (!currentThinkingDiv) return;
            const statusEl = currentThinkingDiv.querySelector('.thinking-status');
            if (statusEl) statusEl.innerHTML = '✅ 完成';
            currentThinkingDiv.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
            // 思考结束后默认折叠，用户可点击展开查看
            currentThinkingDiv.classList.remove('expanded');
            // 记住这张卡片：本轮内若还有迟到的思考增量，应并回它而不是新建
            lastRoundThinkingDiv = currentThinkingDiv;
            currentThinkingDiv = null;
            currentThinkingContent = '';
        };

        /**
         * 处理 THINKING 事件：创建/复用思维链折叠卡片，流式追加推理内容。
         *
         * <p>正文已开始后才到达的思考增量（如 provider 把无换行的推理尾巴压到流结束才发出），
         * 并回本轮已固化的那张卡片。若新建，卡片会落在正文之后，呈现为「回答完了又开始思考」。</p>
         */
        const handleThinking = (event) => {
            if (!currentThinkingDiv && lastRoundThinkingDiv) {
                // 复用本轮已固化的卡片：从现有正文重新播种累积量，
                // 否则下面的 textContent 赋值会把先前的推理内容整段抹掉
                currentThinkingDiv = lastRoundThinkingDiv;
                currentThinkingContent =
                    currentThinkingDiv.querySelector('.thinking-body').textContent || '';
            }
            if (!currentThinkingDiv) {
                finalizeCurrentText();
                currentThinkingDiv = document.createElement('div');
                currentThinkingDiv.className = 'thinking-card expanded';
                currentThinkingDiv.innerHTML = `<div class="thinking-header" onclick="this.parentElement.classList.toggle('expanded')"><span class="tool-call-icon">💭</span><span class="thinking-name">思考过程</span><span class="thinking-status"><span class="tool-call-spinner"></span>思考中</span><span class="tool-call-toggle">▼</span></div><div class="thinking-body"></div>`;
                contentDiv.appendChild(currentThinkingDiv);
                currentThinkingContent = '';
            }
            currentThinkingContent += event.content || '';
            const bodyEl = currentThinkingDiv.querySelector('.thinking-body');
            bodyEl.textContent = currentThinkingContent;
            const cursor = document.createElement('span');
            cursor.className = 'streaming-cursor';
            bodyEl.appendChild(cursor);
        };

        /**
         * 解析工具卡片的归属容器。
         *
         * <p>嵌套执行的工具调用带归属字段（子代理 taskId / 协同角色 agent），卡片需进入
         * 各自的卡片或发言块；并先封闭当前的思考与正文段，保证卡片按时序接在它们之后。
         * 无归属字段的事件属于主 Agent，落回顶层消息容器。</p>
         *
         * @returns {{container: HTMLElement, keyPrefix: string}}
         */
        const resolveToolHost = (event) => {
            const subagentInfo = event.taskId ? subagentCardMap[event.taskId] : null;
            if (subagentInfo) {
                finalizeSubagentThinking(subagentInfo);
                finalizeSubagentContent(subagentInfo);
                return { container: subagentInfo.bodyEl, keyPrefix: toolCardKeyPrefix(event) };
            }
            if (event.agent) {
                const info = getOrCreateCollabBlock(event);
                finalizeCollabThinking(info);
                finalizeCollabContent(info);
                return { container: info.block, keyPrefix: toolCardKeyPrefix(event) };
            }
            finalizeThinking();
            finalizeCurrentText();
            // 工具调用是轮次边界：之后的思考属于新推理阶段，不应再并回上一轮的卡片
            lastRoundThinkingDiv = null;
            return { container: contentDiv, keyPrefix: '' };
        };

        /**
         * 工具卡片索引的归属前缀：主 Agent 与各嵌套执行可能同时调用同名工具，
         * 单用工具名作键会让卡片互相覆盖。
         */
        const toolCardKeyPrefix = (event) => {
            if (event.taskId) return 'subagent:' + event.taskId + ':';
            if (event.agent) return 'collab:' + collabBlockKey(event) + ':';
            return '';
        };

        /**
         * 处理 TOOL_START 事件：创建工具调用卡片，显示工具名和运行状态。
         */
        const handleToolStart = (event) => {
            const host = resolveToolHost(event);

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

            host.container.appendChild(card);
            toolCardMap[host.keyPrefix + toolName] = {
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
            // 与 TOOL_START 同构的归属前缀，定位到同一张卡片
            const cardKey = toolCardKeyPrefix(event) + toolName;
            const cardInfo = toolCardMap[cardKey];

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

                delete toolCardMap[cardKey];
            }

            // 嵌套执行的工具不影响主 Agent 的文本区域状态
            if (event.taskId || event.agent) {
                return;
            }
            // 工具调用结束后，下一段文本需要新建文本区域
            currentTextDiv = null;
            currentTextContent = '';
        };

        /**
         * 处理 SUBAGENT_START 事件：创建子代理卡片。
         */
        const handleSubagentStart = (event) => {
            finalizeThinking();
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
                // 当前思考折叠卡片与累积内容（SUBAGENT_THINKING 事件流式追加目标）
                thinkingDiv: null,
                thinkingBuffer: '',
                // 正文独立容器：与思考折叠块同层共存，避免 innerHTML 重写时相互覆盖
                contentEl: null,
            };
        };

        /**
         * 固化子代理当前的思考折叠卡片：移除流式光标并默认折叠。
         */
        const finalizeSubagentThinking = (cardInfo) => {
            if (!cardInfo.thinkingDiv) return;
            const statusEl = cardInfo.thinkingDiv.querySelector('.thinking-status');
            if (statusEl) statusEl.innerHTML = '✅ 完成';
            cardInfo.thinkingDiv.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
            // 思考结束后默认折叠，用户可点击展开查看
            cardInfo.thinkingDiv.classList.remove('expanded');
            cardInfo.thinkingDiv = null;
            cardInfo.thinkingBuffer = '';
        };

        /**
         * 封闭子代理当前的正文段：移除流式光标，下一段正文将新建容器，
         * 使工具卡片与正文按时序交替排列。
         */
        const finalizeSubagentContent = (cardInfo) => {
            if (!cardInfo.contentEl) return;
            cardInfo.contentEl.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
            cardInfo.contentEl = null;
            cardInfo.contentBuffer = '';
        };

        /**
         * 处理 SUBAGENT_THINKING 事件：在子代理卡片内创建/复用思考折叠卡片，流式追加推理内容。
         */
        const handleSubagentThinking = (event) => {
            const taskId = event.taskId || 'unknown';
            const cardInfo = subagentCardMap[taskId];
            if (!cardInfo) return;

            if (!cardInfo.thinkingDiv) {
                const div = document.createElement('div');
                div.className = 'thinking-card expanded';
                div.innerHTML = `<div class="thinking-header" onclick="this.parentElement.classList.toggle('expanded')"><span class="tool-call-icon">💭</span><span class="thinking-name">思考过程</span><span class="thinking-status"><span class="tool-call-spinner"></span>思考中</span><span class="tool-call-toggle">▼</span></div><div class="thinking-body"></div>`;
                cardInfo.bodyEl.appendChild(div);
                cardInfo.thinkingDiv = div;
                cardInfo.thinkingBuffer = '';
            }
            cardInfo.thinkingBuffer += event.content || '';
            const bodyEl = cardInfo.thinkingDiv.querySelector('.thinking-body');
            bodyEl.textContent = cardInfo.thinkingBuffer;
            const cursor = document.createElement('span');
            cursor.className = 'streaming-cursor';
            bodyEl.appendChild(cursor);
        };

        /**
         * 处理 SUBAGENT_CONTENT 事件：将子代理输出追加到卡片内容区。
         *
         * <p>contentBuffer 是当前正文段的缓冲，不是整个子代理输出：思考卡片与工具卡片
         * 会把正文切成多段，每段各自渲染，保持与卡片的时序。</p>
         */
        const handleSubagentContent = (event) => {
            const taskId = event.taskId || 'unknown';
            const content = event.content || '';
            const cardInfo = subagentCardMap[taskId];
            if (cardInfo) {
                // 正文开始意味着本轮思考结束，固化思考折叠卡片
                finalizeSubagentThinking(cardInfo);
                cardInfo.contentBuffer += content;
                if (!cardInfo.contentEl) {
                    cardInfo.contentEl = document.createElement('div');
                    cardInfo.contentEl.className = 'subagent-content-zone';
                    cardInfo.bodyEl.appendChild(cardInfo.contentEl);
                }
                if (typeof marked !== 'undefined') {
                    cardInfo.contentEl.classList.add('markdown-body');
                    cardInfo.contentEl.style.whiteSpace = '';
                    cardInfo.contentEl.innerHTML = marked.parse(cardInfo.contentBuffer) + '<span class="streaming-cursor"></span>';
                } else {
                    cardInfo.contentEl.style.whiteSpace = 'pre-wrap';
                    cardInfo.contentEl.textContent = cardInfo.contentBuffer;
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
                finalizeSubagentThinking(cardInfo);
                cardInfo.statusEl.className = `subagent-status ${success ? 'success' : 'error'}`;
                cardInfo.statusEl.innerHTML = success ? '✅ 完成' : '❌ 失败';
                // 最终渲染 Markdown 并移除流式光标
                if (typeof marked !== 'undefined' && cardInfo.contentBuffer && cardInfo.contentEl) {
                    cardInfo.contentEl.classList.add('markdown-body');
                    cardInfo.contentEl.style.whiteSpace = '';
                    cardInfo.contentEl.innerHTML = marked.parse(cardInfo.contentBuffer);
                }
                cardInfo.bodyEl.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
                delete subagentCardMap[taskId];
            }
            currentTextDiv = null;
            currentTextContent = '';
        };

        /**
         * 固化指定协同发言块的思考折叠卡片：标记完成、移除流式光标并折叠。
         */
        const finalizeCollabThinking = (info) => {
            if (!info || !info.thinkingDiv) return;
            const statusEl = info.thinkingDiv.querySelector('.thinking-status');
            if (statusEl) statusEl.innerHTML = '✅ 完成';
            info.thinkingDiv.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
            info.thinkingDiv.classList.remove('expanded');
            info.thinkingDiv = null;
            info.thinkingBuffer = '';
        };

        /**
         * 封闭指定协同发言块的正文段：移除流式光标，下一段正文将新建容器。
         */
        const finalizeCollabContent = (info) => {
            if (!info || !info.contentEl) return;
            info.contentEl.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
            info.contentEl = null;
            info.contentBuffer = '';
        };

        /**
         * 固化并释放所有协同发言块（协同结束或流结束时调用）。
         * 并行发言下同时存在多个活跃块，不能只收尾一个，否则其余块的「思考中」转圈不会停。
         */
        const finalizeAllCollabBlocks = () => {
            for (const key of Object.keys(collabBlockMap)) {
                finalizeCollabThinking(collabBlockMap[key]);
                finalizeCollabContent(collabBlockMap[key]);
                delete collabBlockMap[key];
            }
        };

        /**
         * 释放实时拓扑引用（DOM 保留，它持有终版图）。
         *
         * <p>不释放的话，下一轮协同的 TOPOLOGY 事件会把新图画进上一轮的容器，
         * 节点状态也会写串。协同结束、新一轮开始与流结束三个边界都调。</p>
         */
        const finalizeLiveTopo = () => {
            liveCollabTopo = null;
        };

        /**
         * 处理 COLLABORATE_TOPOLOGY 事件：创建/替换实时协同拓扑图。
         *
         * <p>首次到达（初始版，全 PENDING）时在协同卡片顶部创建可折叠容器；
         * 终版到达时全量重绘——边和 Router 节点只有终版才有，用户看到的最后一张图
         * 与刷新后历史里的图一致。渲染复用 renderCollaborationTopology，交互能力相同。</p>
         */
        const handleCollaborateTopology = (event) => {
            const topo = event.topology;
            if (!topo || !Array.isArray(topo.nodes) || !topo.nodes.length) return;
            const collabBody = this._currentCollabBody;
            if (!collabBody) return;

            if (!liveCollabTopo) {
                const wrap = document.createElement('div');
                wrap.className = 'collab-live-topo';
                wrap.innerHTML = `
                    <div class="collab-live-topo-header">
                        <span class="collab-live-topo-icon">🕸</span>
                        <span class="collab-live-topo-title">协同拓扑</span>
                        <span class="collab-live-topo-progress">0/0</span>
                        <span class="collab-timeline-toggle">▼</span>
                    </div>
                    <div class="collab-live-topo-body"></div>`;
                wrap.querySelector('.collab-live-topo-header')
                    .addEventListener('click', () => wrap.classList.toggle('collapsed'));
                // 放在发言块之前：图在上方，逐个点亮的动态不被后续发言冲走视线
                collabBody.insertBefore(wrap, collabBody.firstChild);
                liveCollabTopo = {
                    wrap,
                    bodyEl: wrap.querySelector('.collab-live-topo-body'),
                    progressEl: wrap.querySelector('.collab-live-topo-progress'),
                    colorMap: {},
                    colorCursor: 0,
                    topology: null
                };
            }

            liveCollabTopo.topology = topo;
            (topo.nodes || []).forEach(n => {
                const key = n.label || n.id;
                if (!liveCollabTopo.colorMap[key]) {
                    liveCollabTopo.colorMap[key] =
                        collabRoleColors[(liveCollabTopo.colorCursor++) % collabRoleColors.length];
                }
            });
            this.renderCollaborationTopology(liveCollabTopo.bodyEl, topo, liveCollabTopo.colorMap);
            updateLiveTopoProgress();
        };

        /**
         * 处理 COLLABORATE_NODE 事件：点亮实时拓扑里的一个节点。
         *
         * <p>外科手术式更新——只改状态圆点的颜色，不重建 SVG：保留用户的悬停/选中态
         * 与滚动位置，几十个节点的高频闪烁也不会有可感知的卡顿。</p>
         */
        const handleCollaborateNode = (event) => {
            if (!liveCollabTopo || !liveCollabTopo.topology) return;
            const nodes = liveCollabTopo.topology.nodes || [];
            const idx = nodes.findIndex(n => n.id === event.nodeId);
            if (idx < 0) return; // 初始拓扑外的节点（如 Router）静默忽略，终版拓扑会补上

            nodes[idx].status = event.status || 'PENDING';
            const nodeEl = liveCollabTopo.bodyEl.querySelector(`.topo-node[data-idx="${idx}"]`);
            if (nodeEl) {
                const dot = nodeEl.querySelector('.topo-node-status');
                if (dot) {
                    dot.style.fill = TinyClawConsole.TOPO_STATUS_COLORS[event.status] || '#cbd5e1';
                }
            }
            updateLiveTopoProgress();
        };

        /**
         * 更新实时拓扑头部进度（已完成 / 总数）。
         */
        const updateLiveTopoProgress = () => {
            if (!liveCollabTopo || !liveCollabTopo.progressEl || !liveCollabTopo.topology) return;
            const nodes = liveCollabTopo.topology.nodes || [];
            const done = nodes.filter(n =>
                n.status === 'COMPLETED' || n.status === 'FAILED' || n.status === 'SKIPPED').length;
            liveCollabTopo.progressEl.textContent = `${done}/${nodes.length}`;
            liveCollabTopo.progressEl.classList.toggle('done', nodes.length > 0 && done >= nodes.length);
        };

        /**
         * 协同发言块的索引键：优先用后端下发的 turn（一次发言），
         * 缺失时退到角色名，至少保证同一角色的内容不会散开。
         */
        const collabBlockKey = (event) => event.turn || event.agent || 'Agent';

        /**
         * 获取或创建协同发言块（含 💬 角色名标题）。
         * 每个 turn 一个块，事件交错到达也各归各块；块的位置按首个事件到达顺序确定。
         */
        const getOrCreateCollabBlock = (event) => {
            const key = collabBlockKey(event);
            const existing = collabBlockMap[key];
            if (existing) {
                return existing;
            }
            // 协同发言开始，先封闭主 Agent 的正文
            finalizeCurrentText();
            const agent = event.agent || 'Agent';
            const block = document.createElement('div');
            block.className = 'collab-agent-message';
            block.innerHTML = `<div class="collab-agent-name">💬 ${this.escapeHtml(agent)}</div>`;
            (this._currentCollabBody || contentDiv).appendChild(block);
            collabBlockMap[key] = { agent, block, contentEl: null, contentBuffer: '', thinkingDiv: null, thinkingBuffer: '' };
            return collabBlockMap[key];
        };

        /**
         * 处理 COLLABORATE_AGENT_THINKING 事件：在该 Agent 的发言块内用折叠卡片展示推理过程。
         *
         * <p>思考卡片与正文容器交替追加在发言块末尾，保持「先思考后回答」的时序；
         * 若把思考增量当作正文 chunk 渲染，会逐行碎片化夹在发言里。</p>
         */
        const handleCollabAgentThinking = (event) => {
            const info = getOrCreateCollabBlock(event);
            if (!info.thinkingDiv) {
                // 新一轮思考开始：先封闭上一段正文，让思考卡片落在它之后
                finalizeCollabContent(info);
                const card = document.createElement('div');
                card.className = 'thinking-card expanded';
                card.innerHTML = `<div class="thinking-header" onclick="this.parentElement.classList.toggle('expanded')"><span class="tool-call-icon">💭</span><span class="thinking-name">思考过程</span><span class="thinking-status"><span class="tool-call-spinner"></span>思考中</span><span class="tool-call-toggle">▼</span></div><div class="thinking-body"></div>`;
                info.block.appendChild(card);
                info.thinkingDiv = card;
                info.thinkingBuffer = '';
            }
            info.thinkingBuffer += event.content || '';
            const bodyEl = info.thinkingDiv.querySelector('.thinking-body');
            bodyEl.textContent = info.thinkingBuffer;
            const cursor = document.createElement('span');
            cursor.className = 'streaming-cursor';
            bodyEl.appendChild(cursor);
        };

        /**
         * 处理 APPROVAL_REQUEST 事件：渲染危险命令审批卡片（HITL）。
         * Agent 已在后端阻塞等待，用户点击批准/拒绝后经 /api/chat/interaction 回传，流随后继续。
         */
        const handleApprovalRequest = (event) => {
            finalizeCurrentText();
            const requestId = event.requestId || '';
            const command = event.command || '';
            const reason = event.reason || '';
            const card = document.createElement('div');
            card.className = 'hitl-card hitl-approval';
            card.innerHTML = `
                <div class="hitl-head"><span class="hitl-icon">⚠️</span><span class="hitl-title">危险命令需要审批</span></div>
                ${reason ? `<div class="hitl-reason">${this.escapeHtml(reason)}</div>` : ''}
                <pre class="hitl-command">${this.escapeHtml(command)}</pre>
                <div class="hitl-actions">
                    <button class="btn btn-sm btn-danger" data-decision="deny">拒绝</button>
                    <button class="btn btn-sm btn-primary" data-decision="approve">批准执行</button>
                </div>
                <div class="hitl-status" style="display:none;"></div>`;
            contentDiv.appendChild(card);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;

            const setStatus = (text, cls) => {
                const statusEl = card.querySelector('.hitl-status');
                card.querySelectorAll('.hitl-actions button').forEach(b => { b.disabled = true; });
                statusEl.textContent = text;
                statusEl.className = 'hitl-status ' + (cls || '');
                statusEl.style.display = 'block';
            };
            card.querySelectorAll('.hitl-actions button').forEach(btn => {
                btn.addEventListener('click', async () => {
                    const approved = btn.dataset.decision === 'approve';
                    setStatus(approved ? '已批准，继续执行…' : '已拒绝', approved ? 'hitl-ok' : 'hitl-deny');
                    const ok = await this.submitInteraction(requestId, approved, null);
                    if (!ok) setStatus('审批已失效（可能已超时）', 'hitl-deny');
                });
            });
        };

        /**
         * 处理 ASK_USER 事件：渲染结构化提问卡片（HITL）。
         * 有 options 时提供点选按钮，同时始终提供自由输入框。
         */
        const handleAskUser = (event) => {
            finalizeCurrentText();
            const requestId = event.requestId || '';
            const question = event.question || '';
            const options = Array.isArray(event.options) ? event.options : [];
            const inputId = 'askuser_' + requestId;
            const optionBtns = options.map((o, i) =>
                `<button class="hitl-option" data-opt="${this.escapeAttr(String(i))}">${this.escapeHtml(o)}</button>`).join('');
            const card = document.createElement('div');
            card.className = 'hitl-card hitl-ask';
            card.innerHTML = `
                <div class="hitl-head"><span class="hitl-icon">❓</span><span class="hitl-title">Agent 想向你确认</span></div>
                <div class="hitl-question">${this.escapeHtml(question)}</div>
                ${optionBtns ? `<div class="hitl-options">${optionBtns}</div>` : ''}
                <div class="hitl-input-row">
                    <input type="text" class="form-control" id="${inputId}" placeholder="输入你的回答…" autocomplete="off">
                    <button class="btn btn-sm btn-primary" data-send="1">发送</button>
                </div>
                <div class="hitl-status" style="display:none;"></div>`;
            contentDiv.appendChild(card);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;

            const inputEl = card.querySelector('#' + inputId);
            const setStatus = (text, cls) => {
                const statusEl = card.querySelector('.hitl-status');
                card.querySelectorAll('button, input').forEach(el => { el.disabled = true; });
                statusEl.textContent = text;
                statusEl.className = 'hitl-status ' + (cls || '');
                statusEl.style.display = 'block';
            };
            const send = async (text) => {
                if (!text || !text.trim()) return;
                setStatus('已回答：' + text, 'hitl-ok');
                const ok = await this.submitInteraction(requestId, true, text);
                if (!ok) setStatus('提问已失效（可能已超时）', 'hitl-deny');
            };
            card.querySelectorAll('.hitl-option').forEach(btn => {
                btn.addEventListener('click', () => send(options[Number(btn.dataset.opt)]));
            });
            card.querySelector('[data-send]').addEventListener('click', () => send(inputEl.value));
            inputEl.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') { e.preventDefault(); send(inputEl.value); }
            });
        };

        /**
         * 处理 PLAN 事件：渲染/刷新任务计划清单卡片。
         * 同一条 assistant 消息内多次 PLAN 事件就地更新同一张卡（计划随执行演进）。
         */
        const handlePlan = (event) => {
            finalizeCurrentText();
            const todos = Array.isArray(event.todos) ? event.todos : [];
            if (!currentPlanCard || !currentPlanCard.isConnected) {
                currentPlanCard = document.createElement('div');
                currentPlanCard.className = 'plan-card';
                currentPlanCard.innerHTML = `<div class="plan-head"><span class="plan-icon">📋</span><span class="plan-title">任务计划</span><span class="plan-progress"></span></div><div class="plan-items"></div>`;
                contentDiv.appendChild(currentPlanCard);
            }
            const itemsEl = currentPlanCard.querySelector('.plan-items');
            itemsEl.innerHTML = todos.map(t => {
                const status = t.status || 'pending';
                const icon = status === 'completed' ? '✅' : status === 'in_progress' ? '🔄' : '⬜';
                return `<div class="plan-item" data-status="${this.escapeAttr(status)}"><span class="plan-item-icon">${icon}</span><span class="plan-item-text">${this.escapeHtml(t.content || '')}</span></div>`;
            }).join('');
            const done = todos.filter(t => t.status === 'completed').length;
            const progEl = currentPlanCard.querySelector('.plan-progress');
            if (progEl) progEl.textContent = todos.length ? `${done}/${todos.length}` : '';
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
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
                    // 正文开始，结束当前的思维链卡片
                    finalizeThinking();
                    const textDiv = getOrCreateTextDiv();
                    currentTextContent += event.content || '';
                    assistantRawText += event.content || '';
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
                    handleThinking(event);
                    break;
                }
                case 'RUN_STARTED': {
                    // P1 执行身份元事件：记录 runId（刷新后凭会话恢复）；幂等命中时提示
                    this._activeRunId = event.runId || null;
                    if (event.duplicated) {
                        this.showToast('该请求已在执行中，已忽略重复提交', 'info');
                    }
                    break;
                }
                case 'APPROVAL_REQUEST':
                    handleApprovalRequest(event);
                    break;
                case 'ASK_USER':
                    handleAskUser(event);
                    break;
                case 'PLAN':
                    handlePlan(event);
                    break;
                case 'TOOL_START':
                    this.trackArtifact(event);
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
                case 'SUBAGENT_THINKING':
                    handleSubagentThinking(event);
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
                    finalizeAllCollabBlocks();
                    // 上一轮若未正常释放（如流中断），这里兜底，避免新拓扑画进旧容器
                    finalizeLiveTopo();
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
                    // 流式增量：逐 chunk 追加到本次发言（turn）对应的区域
                    const info = getOrCreateCollabBlock(event);
                    // 正文开始意味着本轮思考结束，固化思考折叠卡片
                    finalizeCollabThinking(info);
                    if (!info.contentEl) {
                        info.contentEl = document.createElement('div');
                        info.contentEl.className = 'collab-agent-content';
                        info.block.appendChild(info.contentEl);
                        info.contentBuffer = '';
                    }
                    info.contentBuffer += event.content || '';
                    if (typeof marked !== 'undefined') {
                        info.contentEl.classList.add('markdown-body');
                        info.contentEl.innerHTML = marked.parse(info.contentBuffer) + '<span class="streaming-cursor"></span>';
                    } else {
                        info.contentEl.innerHTML = this.escapeHtml(info.contentBuffer).replace(/\n/g, '<br>') + '<span class="streaming-cursor"></span>';
                    }
                    break;
                }
                case 'COLLABORATE_AGENT_THINKING': {
                    handleCollabAgentThinking(event);
                    break;
                }
                case 'COLLABORATE_TOPOLOGY': {
                    handleCollaborateTopology(event);
                    break;
                }
                case 'COLLABORATE_NODE': {
                    handleCollaborateNode(event);
                    break;
                }
                case 'COLLABORATE_END': {
                    finalizeAllCollabBlocks();
                    finalizeLiveTopo();
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
            // 使用流式 API，包含图片路径；clientRequestId 为 P1 幂等键（双击/断线重发不重复执行）
            const clientRequestId = this.newClientRequestId();
            this._pendingClientRequestId = clientRequestId;
            const response = await this.authFetch('/api/chat/stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ 
                    message, 
                    sessionId: this.chatSessionId,
                    images: imagePaths.length > 0 ? imagePaths : undefined,
                    clientRequestId,
                    attachmentIds: attachmentIds.length > 0 ? attachmentIds : undefined
                }),
                signal: this.currentAbortController?.signal
            });
            if (!response.ok || !response.body) {
                // 提交被拒：忙碌（409）/未认证等；恢复输入，草稿仍在，可修改后重试
                const errBody = await response.json().catch(() => ({}));
                throw new Error(errBody.error || ('HTTP ' + response.status));
            }
            // 服务端已确认接收：清除草稿（失败恢复逻辑只在响应建立前生效）
            this.clearDraft();

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
                    // 仅当用户停留在底部时自动滚动；上滑阅读历史不被新 token 拉到底
                    if (this.isNearBottom(messagesDiv)) {
                        messagesDiv.scrollTop = messagesDiv.scrollHeight;
                    } else {
                        this.notifyNewStreamContent();
                    }
                }
            }

            // 流结束：将最后一段文本用 Markdown 渲染
            finalizeThinking();
            finalizeAllCollabBlocks();
            finalizeLiveTopo();
            finalizeCurrentText();
            // 移除所有残留的流式光标
            contentDiv.querySelectorAll('.streaming-cursor').forEach(el => el.remove());
            
            // 刷新左侧会话列表
            this.loadChatSessions();
            // P3：流结束后同步服务端成果登记（本轮 write_file/edit_file 已落盘登记）
            this.loadSessionArtifacts();
            // P5：流结束后刷新本轮使用记忆（选中清单与真实注入同源）
            this.loadMemoryUsed();
        } catch (error) {
            if (error.name === 'AbortError') {
                // 用户主动中断，不显示错误
                finalizeCurrentText();
            } else {
                const textDiv = getOrCreateTextDiv();
                textDiv.textContent = 'Error: ' + error.message;
                // 发送失败：正文放回输入框（草稿已同步保存）。
                // 不自动重发——连接中断时消息可能已送达，重发前需用户确认
                this.restoreFailedInput(message);
                this.showToast('发送失败：' + error.message + '，消息已恢复到输入框', 'error');
            }
        } finally {
            // 为流式 assistant 气泡挂载操作栏（复制/重新生成），并记录原始文本供复制使用。
            // 整轮没有任何正文文本（纯工具执行）时不挂操作栏——工具调用步骤无需复制/重新生成。
            if (assistantDiv) {
                assistantDiv._rawContent = assistantRawText;
                if ((assistantRawText || '').trim().length > 0) {
                    this.attachMessageActions(assistantDiv, 'assistant');
                }
            }
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
            // 先通知后端中断（带上会话，避免中断其他通道正在跑的任务）
            try {
                await this.authFetch('/api/chat/abort', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sessionId: this.chatSessionId })
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

        // 后台系统通知（如 subagent 异步任务完成结果）虽以 user 角色存储（作为触发 LLM 的输入），
        // 但本质是系统/模型侧产出，应按模型输出样式展示，而非用户输入气泡样式
        const isSystemNotice = role === 'user' && this.isSystemNoticeContent(content);
        const displayRole = isSystemNotice ? 'assistant' : role;
        div.className = `message ${displayRole}${isSystemNotice ? ' system-notice' : ''}`;

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

        // assistant 消息（含系统通知）使用 Markdown 渲染，user 消息纯文本
        if (displayRole === 'assistant' && typeof marked !== 'undefined') {
            html += `<div class="message-content markdown-body">${marked.parse(content || '')}</div>`;
        } else {
            html += `<div class="message-content">${this.escapeHtml(content || '')}</div>`;
        }

        div.innerHTML = html;
        div._rawContent = content || '';
        this.attachMessageActions(div, displayRole);
        messagesDiv.appendChild(div);
        // 仅在用户处于底部时跟随滚动，上滑阅读不被新消息打断
        if (scroll && this.isNearBottom(messagesDiv)) {
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }
    }

    /**
     * 为消息气泡追加操作栏（复制 / 编辑重发）。
     *
     * <p>幂等：已存在操作栏时不重复追加，避免历史回放与流式收尾重复挂载。
     * 操作栏作为 {@code .message-content} 气泡的兄弟节点追加，位于气泡下方。</p>
     *
     * @param {HTMLElement} messageEl - {@code .message} 气泡元素
     * @param {'user'|'assistant'} role - 展示角色，决定可用操作（user 额外提供编辑重发）
     */
    attachMessageActions(messageEl, role) {
        if (!messageEl || messageEl.querySelector('.message-actions')) return;
        const bar = document.createElement('div');
        bar.className = 'message-actions';
        const buttons = ['<button class="msg-action-btn" data-action="copy" title="复制">⧉</button>'];
        if (role === 'user') {
            buttons.push('<button class="msg-action-btn" data-action="edit" title="编辑并重发">✎</button>');
        }
        if (role === 'assistant') {
            buttons.push('<button class="msg-action-btn" data-action="regen" title="重新生成（派生分支会话）">↻</button>');
        }
        if (role === 'assistant' && this.feedbackEnabled) {
            buttons.push('<button class="msg-action-btn" data-action="up" title="有帮助">👍</button>');
            buttons.push('<button class="msg-action-btn" data-action="down" title="需改进">👎</button>');
        }
        bar.innerHTML = buttons.join('');
        bar.addEventListener('click', (e) => {
            const btn = e.target.closest('.msg-action-btn');
            if (!btn) return;
            const action = btn.dataset.action;
            if (action === 'copy') this.copyMessageText(messageEl, btn);
            else if (action === 'edit') this.editUserMessage(messageEl);
            else if (action === 'regen') this.regenerateFrom(messageEl, btn);
            else if (action === 'up' || action === 'down') this.submitFeedback(action, btn);
        });
        messageEl.appendChild(bar);
    }

    /**
     * 复制消息原始文本到剪贴板，成功后按钮短暂显示对勾。
     * 优先用气泡上缓存的原始 Markdown（{@code _rawContent}），回退到渲染后的可见文本。
     */
    async copyMessageText(messageEl, btn) {
        const raw = messageEl._rawContent;
        const contentEl = messageEl.querySelector('.message-content');
        const text = (raw != null && raw !== '')
            ? raw
            : (contentEl ? contentEl.innerText : messageEl.innerText);
        try {
            await navigator.clipboard.writeText(text || '');
            this.flashActionButton(btn, '✓');
        } catch (e) {
            // clipboard API 在非安全上下文（http 内网）可能不可用，回退到临时 textarea + execCommand
            this.legacyCopy(text || '');
            this.flashActionButton(btn, '✓');
        }
    }

    /**
     * clipboard API 不可用时的降级复制实现。
     */
    legacyCopy(text) {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } catch (e) { /* 忽略：复制失败不阻断交互 */ }
        ta.remove();
    }

    /**
     * 操作按钮点击后的短暂视觉反馈（切换图标 + 高亮，1.2s 后还原）。
     */
    flashActionButton(btn, symbol) {
        if (!btn) return;
        const original = btn.textContent;
        btn.textContent = symbol;
        btn.classList.add('msg-action-done');
        setTimeout(() => {
            btn.textContent = original;
            btn.classList.remove('msg-action-done');
        }, 1200);
    }

    /**
     * 编辑重发：把用户消息原文载入输入框，供修改后作为新一轮发送。
     */
    editUserMessage(messageEl) {
        const raw = messageEl._rawContent || '';
        const input = document.getElementById('chatInput');
        if (!input) return;
        input.value = raw;
        input.focus();
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
        this.showToast('已载入输入框，编辑后发送', 'info');
    }

    /**
     * 重新生成 / 回溯重发：以 fork 分支的方式重跑某一轮回答。
     *
     * <p>遵循后端「不可变转录」约束——不删改原会话，而是派生一个复制了截断点之前
     * 全部转录的新分支会话，再把触发该轮的 user 提问在新分支里重发，从而得到一次
     * 全新的回答。截断点取该 assistant 气泡之前最近的 user 消息下标（历史回放气泡
     * 带 data-msg-index）；实时刚生成、还没有下标的气泡则传 -1，由后端回退到
     * 「最后一条 user 消息」，即重跑最新一轮。</p>
     *
     * @param {HTMLElement} messageEl - 触发重新生成的 assistant 气泡
     * @param {HTMLElement} btn - 触发的按钮，用于状态反馈
     */
    async regenerateFrom(messageEl, btn) {
        if (this.currentAbortController) {
            this.showToast('请等待当前任务完成后再重新生成', 'info');
            return;
        }
        // 计算 fork 截断点：该 assistant 轮次对应的 user 提问的绝对下标
        let cutIndex = -1;
        const startIdx = (messageEl && messageEl.dataset)
            ? parseInt(messageEl.dataset.msgIndex, 10) : NaN;
        if (!isNaN(startIdx)) {
            let best = -1;
            document.querySelectorAll('#chatMessages .message.user[data-msg-index]').forEach(b => {
                const bi = parseInt(b.dataset.msgIndex, 10);
                if (!isNaN(bi) && bi < startIdx && bi > best) best = bi;
            });
            cutIndex = best;
        }
        const sourceKey = this.chatSessionId;
        if (btn) btn.disabled = true;
        try {
            const resp = await this.authFetch(
                `/api/sessions/${encodeURIComponent(sourceKey)}/fork`,
                {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(cutIndex >= 0 ? { cutIndex } : {})
                });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('派生分支失败 (' + resp.status + ')'), 'error');
                return;
            }
            const data = await resp.json();
            const newKey = data.sessionKey;
            if (!newKey) { this.showToast('派生分支失败：未返回新会话', 'error'); return; }

            // 切换到分支会话并重载已复制的历史前缀
            this.chatSessionId = newKey;
            localStorage.setItem('tinyclaw_chat_session', newKey);
            await this.loadChatHistory();
            this.loadChatSessions();

            const replay = data.replayMessage;
            if (!replay || !replay.content) {
                // 截断点处没有可重发的 user 提问：仅停留在分支会话
                this.showToast('已派生分支会话（无可重发的提问）', 'success');
                return;
            }
            // 把提问塞回输入框；图片走已上传路径通道（sendMessage 内消费 _replayImagePaths）
            if (Array.isArray(replay.images) && replay.images.length) {
                this._replayImagePaths = replay.images;
            }
            const input = document.getElementById('chatInput');
            input.value = replay.content;
            this.saveDraft(replay.content);
            this.showToast('已在新分支重新生成', 'info');
            await this.sendMessage();
        } catch (e) {
            this.showToast('重新生成失败：' + e.message, 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    /**
     * 提交对 assistant 回复的显式评价（👍/👎）到进化反馈系统。
     * 后端未启用进化时返回 501，此时提示用户而不报错。
     *
     * @param {'up'|'down'} rating - 评价方向
     * @param {HTMLElement} btn - 触发的按钮，用于成功后高亮反馈
     */
    async submitFeedback(rating, btn) {
        try {
            const resp = await this.authFetch('/api/feedback', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: this.chatSessionId, rating })
            });
            if (resp.ok) {
                this.flashActionButton(btn, '✓');
                this.showToast(rating === 'up' ? '感谢反馈' : '已记录，我们会持续改进', 'success');
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('反馈提交失败 (' + resp.status + ')'), 'error');
            }
        } catch (e) {
            this.showToast('网络错误，反馈未提交', 'error');
        }
    }

    /**
     * 回传一次 HITL 交互决策（审批或提问回答）到后端，唤醒阻塞中的工具执行。
     *
     * @param {string} requestId - 交互请求 id
     * @param {boolean} approved - 审批结果（提问类可传 true）
     * @param {string|null} response - 回答文本（审批类传 null）
     * @returns {Promise<boolean>} 后端是否成功唤醒等待中的交互（false 表示已失效/超时）
     */
    async submitInteraction(requestId, approved, response) {
        try {
            const resp = await this.authFetch('/api/chat/interaction', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ requestId, approved, response })
            });
            if (!resp.ok) return false;
            const data = await resp.json().catch(() => ({}));
            return !!data.resolved;
        } catch (e) {
            console.error('Failed to submit interaction:', e);
            return false;
        }
    }

    // ==================== Artifacts（本次会话产生的文件） ====================

    // P3：服务端登记的成果列表（ArtifactRecord 视图）
    serverArtifacts = [];

    /**
     * P3：从服务端加载会话的持久化成果（带加载序号守卫）。
     * 接口不可用（旧版本）时静默降级为本地追踪。
     */
    async loadSessionArtifacts(loadSeq) {
        try {
            const resp = await this.authFetch('/api/artifacts?sessionId=' + encodeURIComponent(this.chatSessionId));
            if (!resp.ok) return;
            const data = await resp.json();
            if (loadSeq !== undefined && loadSeq !== this._chatHistoryLoadSeq) return;
            this.serverArtifacts = data.artifacts || [];
            this.updateArtifactsBadge();
        } catch (e) {
            // 接口不可用（旧版本后端）：保持本地追踪
        }
    }

    /**
     * 从 TOOL_START 事件中提取 write_file/edit_file 的目标路径，本地即时提示。
     * P3 起权威登记在服务端（写盘成功后记录）；本地列表仅用于流式过程中的即时角标，
     * 流结束/刷新后由 loadSessionArtifacts 对齐服务端数据。
     */
    trackArtifact(event) {
        const tool = event && event.tool;
        if (tool !== 'write_file' && tool !== 'edit_file') return;
        const args = event.args || {};
        const path = args.path || args.file_path;
        if (!path || typeof path !== 'string') return;
        const idx = this.sessionArtifacts.indexOf(path);
        if (idx >= 0) this.sessionArtifacts.splice(idx, 1);
        this.sessionArtifacts.push(path);
        this.updateArtifactsBadge();
    }

    /** 更新 Artifacts 按钮上的数量角标（服务端登记数为准，本地仅兜底）。 */
    updateArtifactsBadge() {
        const badge = document.getElementById('artifactsBadge');
        if (!badge) return;
        const n = Math.max(this.serverArtifacts.length, this.sessionArtifacts.length);
        badge.textContent = String(n);
        badge.style.display = n > 0 ? 'inline-flex' : 'none';
    }

    /** 打开 Artifacts 面板（模态）：服务端登记成果 + 版本预览/下载/继续修改。 */
    openArtifactsPanel() {
        const artifacts = this.serverArtifacts;
        const localOnly = this.sessionArtifacts.filter(p =>
            !artifacts.some(a => a.path && a.path.endsWith(p)));
        const body = (artifacts.length === 0 && localOnly.length === 0)
            ? '<p class="empty-state">本次会话尚未产生文件。Agent 调用 write_file / edit_file 后会在此列出。</p>'
            : `<div class="artifacts-list">${artifacts.map(a => this.renderArtifactItem(a)).join('')}
                ${localOnly.map(p => `
                <div class="artifact-item artifact-local" data-path="${this.escapeAttr(p)}">
                    <span class="artifact-icon">📄</span>
                    <span class="artifact-name">${this.escapeHtml(this.basename(p))}</span>
                    <span class="artifact-path">${this.escapeHtml(p)}（待服务端登记）</span>
                </div>`).join('')}</div>`;
        this.showModal('Artifacts · 本次会话文件', body, null);
        document.getElementById('modalConfirm').style.display = 'none';
        document.querySelectorAll('#modalBody .artifact-item[data-id]').forEach(el => {
            el.addEventListener('click', () => this.previewArtifactById(el.dataset.id));
        });
        document.querySelectorAll('#modalBody .artifact-item[data-path]').forEach(el => {
            el.addEventListener('click', () => this.previewArtifact(el.dataset.path));
        });
    }

    /**
     * P3：渲染单个服务端登记成果条目（版本数 / 存在性 / 继续修改入口）。
     */
    renderArtifactItem(a) {
        const missing = a.exists === false;
        const rev = a.revision || 1;
        return `
                <div class="artifact-item ${missing ? 'artifact-missing' : ''}" data-id="${this.escapeAttr(a.id)}">
                    <span class="artifact-icon">${missing ? '🩶' : '📄'}</span>
                    <span class="artifact-name">${this.escapeHtml(a.name || '')}</span>
                    <span class="artifact-path">${this.escapeHtml(a.path || '')}</span>
                    <span class="artifact-rev" title="写入版本数">v${rev}</span>
                    ${missing ? '<span class="artifact-missing-tag">原文件已移除</span>' : ''}
                </div>`;
    }

    /**
     * P3：按成果 ID 预览：拉取版本列表与当前内容，Markdown 渲染 + 版本切换 + 下载 + 继续修改。
     */
    async previewArtifactById(id) {
        let artifact = (this.serverArtifacts || []).find(a => a.id === id);
        if (!artifact) {
            // P6：Cron 历史等场景引用的成果不在当前会话缓存中，从服务端拉取详情
            try {
                const resp = await this.authFetch(`/api/artifacts/${encodeURIComponent(id)}`);
                if (!resp.ok) {
                    this.showToast('成果不存在或已清理 (' + resp.status + ')', 'error');
                    return;
                }
                artifact = await resp.json();
            } catch (e) {
                this.showToast('拉取成果失败：' + e.message, 'error');
                return;
            }
        }
        try {
            const [contentResp, versionsResp] = await Promise.allSettled([
                this.authFetch(`/api/artifacts/${encodeURIComponent(id)}/content?revision=0`),
                this.authFetch(`/api/artifacts/${encodeURIComponent(id)}/versions`)
            ]);
            const contentData = contentResp.status === 'fulfilled' && contentResp.value.ok
                ? await contentResp.value.json() : null;
            const versionsData = versionsResp.status === 'fulfilled' && versionsResp.value.ok
                ? await versionsResp.value.json() : null;
            const versions = (versionsData && versionsData.versions) || [];
            const content = contentData && contentData.content;
            const isMd = (artifact.mediaType || '').includes('markdown');
            const rendered = content != null
                ? (isMd && typeof marked !== 'undefined'
                    ? `<div class="markdown-body artifact-preview-md">${marked.parse(content)}</div>`
                    : `<pre class="artifact-preview">${this.escapeHtml(content)}</pre>`)
                : `<p class="empty-state">${this.escapeHtml(contentData && contentData.error || '内容不可预览（可能为二进制或原文件已移除），可下载历史版本。')}</p>`;
            const versionOptions = versions.map(v =>
                `<option value="${v.revision}" ${v.isCurrent ? 'selected' : ''}>v${v.revision}${v.isCurrent ? '（当前）' : ''}</option>`).join('');
            const title = this.basename(artifact.path);
            this.showModal(`${title} · v${artifact.revision}`, `
                <div class="artifact-viewer">
                    <div class="artifact-viewer-toolbar">
                        <select class="form-control artifact-version-select" id="artifactVersionSelect">${versionOptions}</select>
                        <button class="btn btn-secondary btn-sm" id="artifactDownloadBtn">下载此版本</button>
                        <button class="btn btn-primary btn-sm" id="artifactContinueBtn">继续修改</button>
                    </div>
                    ${rendered}
                </div>`, null);
            document.getElementById('modalConfirm').style.display = 'none';
            const select = document.getElementById('artifactVersionSelect');
            if (select) select.addEventListener('change', () => this.previewArtifactVersion(id, select.value));
            const dl = document.getElementById('artifactDownloadBtn');
            if (dl) dl.addEventListener('click', () => this.downloadArtifact(id, select ? select.value : '0'));
            const cont = document.getElementById('artifactContinueBtn');
            if (cont) cont.addEventListener('click', () => this.continueEditingArtifact(artifact));
        } catch (e) {
            this.showToast('预览失败：' + e.message, 'error');
        }
    }

    /**
     * P3：切换版本重新拉取内容并更新模态体。
     */
    async previewArtifactVersion(id, revision) {
        try {
            const resp = await this.authFetch(`/api/artifacts/${encodeURIComponent(id)}/content?revision=${encodeURIComponent(revision)}`);
            const data = await resp.json();
            const container = document.querySelector('#modalBody .artifact-viewer');
            if (!container) return;
            const preview = container.querySelector('.artifact-preview, .artifact-preview-md');
            if (!resp.ok || data.content == null) {
                if (preview) preview.outerHTML = `<p class="empty-state">${this.escapeHtml(data.error || '该版本不可预览')}</p>`;
                return;
            }
            const isMd = data.mediaType && data.mediaType.includes('markdown');
            const html = isMd && typeof marked !== 'undefined'
                ? `<div class="markdown-body artifact-preview-md">${marked.parse(data.content)}</div>`
                : `<pre class="artifact-preview">${this.escapeHtml(data.content)}</pre>`;
            if (preview) preview.outerHTML = html;
        } catch (e) {
            this.showToast('版本加载失败：' + e.message, 'error');
        }
    }

    /**
     * P3：下载指定版本（认证 fetch 取 Blob，凭据不进 URL）。
     */
    async downloadArtifact(id, revision) {
        try {
            const resp = await this.authFetch(`/api/artifacts/${encodeURIComponent(id)}/download?revision=${encodeURIComponent(revision)}`);
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('下载失败 (' + resp.status + ')'), 'error');
                return;
            }
            const blob = await resp.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = this.basename(this.serverArtifacts.find(x => x.id === id)?.path || `artifact-${id}`);
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        } catch (e) {
            this.showToast('下载失败：' + e.message, 'error');
        }
    }

    /**
     * P3：“继续修改”：把文件路径与当前版本塞进输入框模板，走正常聊天链路。
     */
    continueEditingArtifact(artifact) {
        const input = document.getElementById('chatInput');
        if (!input) return;
        const template = `请继续修改文件 ${artifact.path}（当前 v${artifact.revision}）。要求：`;
        input.value = template;
        input.focus();
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
        this.saveDraft(template);
        this.hideModal();
        this.showToast('已填入修改请求模板，补充要求后发送', 'info');
    }

    /**
     * 预览某个产物文件的内容（尽力而为：工作空间外的文件会 403 并提示）。
     */
    async previewArtifact(path) {
        try {
            const resp = await this.authFetch('/api/workspace/files/' + encodeURIComponent(path));
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('无法预览 (' + resp.status + ')'), 'error');
                return;
            }
            const data = await resp.json();
            const content = data.content || '';
            this.showModal(this.basename(path), `
                <pre class="artifact-preview">${this.escapeHtml(content)}</pre>
                <div class="artifact-preview-actions">
                    <button class="btn btn-secondary btn-sm" id="artifactDownloadBtn">下载</button>
                </div>`, null);
            document.getElementById('modalConfirm').style.display = 'none';
            const dl = document.getElementById('artifactDownloadBtn');
            if (dl) dl.addEventListener('click', () => this.downloadText(this.basename(path), content));
        } catch (e) {
            this.showToast('预览失败：' + e.message, 'error');
        }
    }

    /** 取路径的文件名部分。 */
    basename(p) {
        const s = String(p || '');
        const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** 前端触发文本文件下载。 */
    downloadText(filename, text) {
        const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    // ==================== Memory（长期记忆管理） ====================

    /** 记忆列表筛选与分页状态（P5：可控记忆——筛选/分页在服务端执行）。 */
    _memoryFilters = { q: '', scope: '', source: '', offset: 0, limit: 50 };

    /**
     * 加载记忆页：绑定筛选栏与工具栏按钮，携带筛选/分页参数拉取记忆条目列表。
     * 记忆系统未就绪（501）时展示提示而非报错。
     */
    async loadMemory() {
        const addBtn = document.getElementById('addMemoryBtn');
        const refreshBtn = document.getElementById('refreshMemoryBtn');
        if (addBtn) addBtn.onclick = () => this.showMemoryForm(null);
        if (refreshBtn) refreshBtn.onclick = () => this.loadMemory();
        this.bindMemoryFilters();

        const list = document.getElementById('memoryList');
        if (!list) return;
        try {
            const params = new URLSearchParams();
            if (this._memoryFilters.q) params.set('q', this._memoryFilters.q);
            if (this._memoryFilters.scope) params.set('scope', this._memoryFilters.scope);
            if (this._memoryFilters.source) params.set('source', this._memoryFilters.source);
            params.set('limit', String(this._memoryFilters.limit));
            params.set('offset', String(this._memoryFilters.offset));
            const resp = await this.authFetch('/api/memory?' + params.toString());
            if (resp.status === 501) {
                list.innerHTML = '<p class="empty-state">记忆系统未就绪（provider 未初始化）。</p>';
                this.renderMemoryPagination({ total: 0 });
                return;
            }
            if (!resp.ok) {
                list.innerHTML = '<p class="empty-state">加载失败 (' + resp.status + ')</p>';
                return;
            }
            const data = await resp.json();
            this.renderMemory(data.entries || []);
            this.renderMemoryPagination(data);
        } catch (e) {
            list.innerHTML = '<p class="empty-state">加载失败：' + this.escapeHtml(e.message) + '</p>';
        }
    }

    /**
     * 绑定筛选栏控件（关键词/归属域/来源/每页条数/筛选/重置）。
     * scope 与 source 下拉选项从当前列表数据动态收集，避免硬编码。
     */
    bindMemoryFilters() {
        if (this._memoryFiltersBound) return;
        const search = document.getElementById('memorySearchInput');
        const scope = document.getElementById('memoryScopeSelect');
        const source = document.getElementById('memorySourceSelect');
        const pageSize = document.getElementById('memoryPageSizeSelect');
        const filterBtn = document.getElementById('memoryFilterBtn');
        const resetBtn = document.getElementById('memoryFilterResetBtn');
        if (!filterBtn) return;
        filterBtn.addEventListener('click', () => {
            this._memoryFilters.q = search ? search.value.trim() : '';
            this._memoryFilters.scope = scope ? scope.value : '';
            this._memoryFilters.source = source ? source.value : '';
            this._memoryFilters.offset = 0;
            this.loadMemory();
        });
        if (search) search.addEventListener('keydown', e => {
            if (e.key === 'Enter') filterBtn.click();
        });
        if (resetBtn) resetBtn.addEventListener('click', () => {
            if (search) search.value = '';
            if (scope) scope.value = '';
            if (source) source.value = '';
            this._memoryFilters = { q: '', scope: '', source: '', offset: 0, limit: this._memoryFilters.limit };
            this.loadMemory();
        });
        if (pageSize) pageSize.addEventListener('change', () => {
            this._memoryFilters.limit = parseInt(pageSize.value, 10) || 50;
            this._memoryFilters.offset = 0;
            this.loadMemory();
        });
        this._memoryFiltersBound = true;
    }

    /**
     * 渲染记忆分页条（上一页/下一页 + 过滤后总数）。offset 超界时服务端已铻制。
     */
    renderMemoryPagination(data) {
        const pager = document.getElementById('memoryPagination');
        if (!pager) return;
        const total = data.total || 0;
        const offset = data.offset || 0;
        const limit = data.limit || this._memoryFilters.limit;
        if (total === 0) { pager.innerHTML = ''; return; }
        const from = offset + 1;
        const to = Math.min(offset + limit, total);
        const hasPrev = offset > 0;
        const hasNext = offset + limit < total;
        pager.innerHTML = `
            <button class="btn btn-secondary btn-sm" data-act="prev" ${hasPrev ? '' : 'disabled'}>← Prev</button>
            <span class="memory-page-info">${from}-${to} / ${total}</span>
            <button class="btn btn-secondary btn-sm" data-act="next" ${hasNext ? '' : 'disabled'}>Next →</button>`;
        pager.querySelectorAll('button').forEach(btn => {
            btn.addEventListener('click', () => {
                if (btn.disabled) return;
                const delta = btn.dataset.act === 'prev' ? -limit : limit;
                this._memoryFilters.offset = Math.max(0, offset + delta);
                this.loadMemory();
            });
        });
    }

    /**
     * 渲染记忆条目列表，采用事件委托绑定编辑/删除（避免 id 拼接进 onclick 字符串）。
     * 带有 sourceSessionKey 的条目展示可点击的来源会话徽标，点击前先经服务端查询确认
     * 会话存在（合法 ID 关联），不存在则提示“来源会话已不存在”。
     */
    renderMemory(entries) {
        const list = document.getElementById('memoryList');
        if (!list) return;
        if (entries.length === 0) {
            list.innerHTML = '<p class="empty-state">暂无记忆条目</p>';
            return;
        }
        this.refreshMemoryFilterOptions(entries);
        list.innerHTML = entries.map(e => {
            const tags = Array.isArray(e.tags) ? e.tags : [];
            const tagsHtml = tags.length
                ? `<div class="memory-tags">${tags.map(t => `<span class="memory-tag">${this.escapeHtml(t)}</span>`).join('')}</div>`
                : '';
            const imp = e.importance != null ? Number(e.importance).toFixed(2) : '—';
            const when = e.createdAt ? this.timeAgo(Date.parse(e.createdAt)) : '';
            const origin = e.sourceSessionKey
                ? `<button class="memory-origin" data-origin="${this.escapeAttr(e.sourceSessionKey)}" title="跳转到来源会话">来源会话 ${this.escapeHtml(e.sourceSessionKey)}</button>`
                : '';
            return `
            <div class="memory-item" data-id="${this.escapeAttr(e.id)}">
                <div class="memory-item-head">
                    <span class="badge badge-outline">${this.escapeHtml(e.scope || 'global')}</span>
                    <span class="memory-imp">重要度 ${imp}</span>
                    <span class="memory-meta">${this.escapeHtml(e.source || '')}${when ? ' · ' + when : ''}</span>
                    ${origin}
                </div>
                <div class="memory-content">${this.escapeHtml(e.content || '')}</div>
                ${tagsHtml}
                <div class="memory-actions">
                    <button class="btn btn-text" data-act="edit">Edit</button>
                    <button class="btn btn-text btn-danger" data-act="delete">Delete</button>
                </div>
            </div>`;
        }).join('');
        list.querySelectorAll('.memory-item').forEach(item => {
            const id = item.dataset.id;
            const entry = entries.find(x => String(x.id) === String(id));
            const editBtn = item.querySelector('[data-act="edit"]');
            const delBtn = item.querySelector('[data-act="delete"]');
            if (editBtn) editBtn.addEventListener('click', () => this.showMemoryForm(entry));
            if (delBtn) delBtn.addEventListener('click', () => this.deleteMemory(id));
        });
        list.querySelectorAll('.memory-origin').forEach(btn => {
            btn.addEventListener('click', () => this.jumpToMemoryOrigin(btn.dataset.origin));
        });
    }

    /**
     * 从当前页数据动态补充 scope/source 下拉选项（保留选中值，只加不删）。
     */
    refreshMemoryFilterOptions(entries) {
        const scopeSelect = document.getElementById('memoryScopeSelect');
        const sourceSelect = document.getElementById('memorySourceSelect');
        if (scopeSelect) {
            const known = new Set(Array.from(scopeSelect.options).map(o => o.value));
            entries.forEach(e => {
                const v = String(e.scope || '').trim();
                if (v && !known.has(v)) {
                    known.add(v);
                    scopeSelect.add(new Option(v, v));
                }
            });
            scopeSelect.value = this._memoryFilters.scope;
        }
        if (sourceSelect) {
            const known = new Set(Array.from(sourceSelect.options).map(o => o.value));
            entries.forEach(e => {
                const v = String(e.source || '').trim();
                if (v && !known.has(v)) {
                    known.add(v);
                    sourceSelect.add(new Option(v, v));
                }
            });
            sourceSelect.value = this._memoryFilters.source;
        }
    }

    /**
     * 跳转到记忆的来源会话：先服务端查询会话存在再切换（不接受任意路径）。
     * 会话已删除时提示而不报错。
     */
    async jumpToMemoryOrigin(sessionKey) {
        if (!sessionKey) return;
        if (sessionKey === this.chatSessionId) {
            this.showToast('当前就在该会话中', 'info');
            return;
        }
        try {
            const resp = await this.authFetch(`/api/sessions/${encodeURIComponent(sessionKey)}`);
            if (resp.status === 404) {
                this.showToast('来源会话已不存在（历史来源未记录或已删除）', 'error');
                return;
            }
            if (!resp.ok) {
                this.showToast('查询来源会话失败 (' + resp.status + ')', 'error');
                return;
            }
            this.navigateTo('chat');
            this.switchChatSession(sessionKey);
            this.showToast('已切换到来源会话', 'success');
        } catch (e) {
            this.showToast('查询来源会话失败：' + e.message, 'error');
        }
    }

    /**
     * 弹出记忆表单：entry 为 null 时新增，否则编辑。新增时可选填来源会话
     * （默认带出当前聊天会话，方便回溯），编辑时来源会话不可改（避免误改回溯链）。保存后刷新列表。
     */
    showMemoryForm(entry) {
        const isEdit = !!entry;
        const content = isEdit ? (entry.content || '') : '';
        const importance = isEdit && entry.importance != null ? entry.importance : 0.5;
        const tags = isEdit && Array.isArray(entry.tags) ? entry.tags.join(', ') : '';
        const defaultSessionKey = (!isEdit && this.chatSessionId) ? this.chatSessionId : '';
        const originField = isEdit
            ? (entry.sourceSessionKey
                ? `<div class="form-group"><label>Source Session</label>
                    <input class="form-control" value="${this.escapeAttr(entry.sourceSessionKey)}" readonly>
                 </div>`
                : '<p class="form-hint">历史来源未记录</p>')
            : `<div class="form-group">
                    <label>Source Session (optional)</label>
                    <input id="memSourceSession" type="text" class="form-control" value="${this.escapeAttr(defaultSessionKey)}" placeholder="e.g. ${this.escapeAttr(this.chatSessionId || 'web:default')}">
                </div>`;
        this.showModal(isEdit ? 'Edit Memory' : 'Add Memory', `
            <div class="form-group">
                <label>Content</label>
                <textarea id="memContent" class="form-control" rows="4" style="width:100%;resize:vertical;">${this.escapeHtml(content)}</textarea>
            </div>
            <div class="form-group">
                <label>Importance (0.0 ~ 1.0)</label>
                <input id="memImportance" type="number" class="form-control" step="0.05" min="0" max="1" value="${importance}">
            </div>
            <div class="form-group">
                <label>Tags (comma separated)</label>
                <input id="memTags" type="text" class="form-control" value="${this.escapeAttr(tags)}">
            </div>
            ${originField}
        `, async () => {
            const c = document.getElementById('memContent').value.trim();
            if (!c) { this.showToast('内容不能为空', 'error'); return; }
            const imp = parseFloat(document.getElementById('memImportance').value || '0.5');
            const tagList = document.getElementById('memTags').value.split(',').map(s => s.trim()).filter(Boolean);
            const payload = { content: c, importance: imp, tags: tagList };
            const sessionInput = document.getElementById('memSourceSession');
            if (sessionInput && sessionInput.value.trim()) {
                payload.sourceSessionKey = sessionInput.value.trim();
            }
            const url = isEdit ? `/api/memory/${encodeURIComponent(entry.id)}` : '/api/memory';
            const method = isEdit ? 'PUT' : 'POST';
            const resp = await this.authFetch(url, {
                method,
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (resp.ok) {
                this.showToast(isEdit ? '记忆已更新' : '记忆已添加', 'success');
                this.loadMemory();
                // P5：聊天内纠正后同步刷新本轮使用面板（仅影响后续轮次）
                this.loadMemoryUsed();
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('保存失败 (' + resp.status + ')'), 'error');
            }
        });
        document.getElementById('modalConfirm').textContent = 'Save';
        document.getElementById('modalConfirm').style.display = 'block';
    }

    /**
     * 删除一条记忆（二次确认后）。
     */
    async deleteMemory(id) {
        if (!confirm('删除这条记忆？此操作不可撤销。')) return;
        try {
            const resp = await this.authFetch(`/api/memory/${encodeURIComponent(id)}`, { method: 'DELETE' });
            if (resp.ok) {
                this.showToast('记忆已删除', 'success');
                this.loadMemory();
                // P5：聊天内删除后同步刷新本轮使用面板（仅影响后续轮次）
                this.loadMemoryUsed();
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('删除失败 (' + resp.status + ')'), 'error');
            }
        } catch (e) {
            this.showToast('网络错误，删除失败', 'error');
        }
    }

    // ==================== Projects（项目空间，P4） ====================

    /**
     * 加载项目列表：项目 = 名称 + 指令 + 显式共享的资料引用，供长期工作组织。
     * 项目页不替代全局核心文件编辑器（workspace 页职责不变）。
     */
    async loadProjects() {
        const addBtn = document.getElementById('addProjectBtn');
        const refreshBtn = document.getElementById('refreshProjectsBtn');
        if (addBtn) addBtn.onclick = () => this.showProjectForm(null);
        if (refreshBtn) refreshBtn.onclick = () => this.loadProjects();

        const list = document.getElementById('projectList');
        if (!list) return;
        try {
            const resp = await this.authFetch('/api/projects');
            if (!resp.ok) {
                list.innerHTML = '<p class="empty-state">加载失败 (' + resp.status + ')。若为 404，说明本实例未启用项目能力。</p>';
                return;
            }
            const data = await resp.json();
            this.renderProjects(data.projects || []);
        } catch (e) {
            list.innerHTML = '<p class="empty-state">加载失败：' + this.escapeHtml(e.message) + '</p>';
        }
    }

    /** 渲染项目卡片（指令折叠展示；归档半透明；提供归属会话查看入口）。 */
    renderProjects(projects) {
        const list = document.getElementById('projectList');
        if (!list) return;
        if (!projects.length) {
            list.innerHTML = '<p class="empty-state">暂无项目——新建一个把长期工作的指令与资料组织起来。</p>';
            return;
        }
        list.innerHTML = projects.map(p => {
            const instructions = p.instructions
                ? `<details class="project-instructions"><summary>项目指令</summary><pre>${this.escapeHtml(p.instructions)}</pre></details>`
                : '<p class="form-hint">未设置项目指令</p>';
            const archivedBadge = p.archived ? '<span class="badge badge-outline">已归档</span>' : '';
            return `
            <div class="project-item${p.archived ? ' item-archived' : ''}" data-id="${this.escapeAttr(p.id)}">
                <div class="project-item-head">
                    <span class="project-name">${this.escapeHtml(p.name)}</span>
                    ${archivedBadge}
                    <span class="project-meta">rev ${p.revision} · 资料 ${p.resourceCount} 个 · 更新于 ${this.timeAgo(Date.parse(p.updatedAt))}</span>
                </div>
                ${instructions}
                <div class="project-actions">
                    <button class="btn btn-text btn-sm" data-act="edit">编辑</button>
                    <button class="btn btn-text btn-sm" data-act="sessions">会话</button>
                    <button class="btn btn-text btn-sm" data-act="archive">${p.archived ? '取消归档' : '归档'}</button>
                    <button class="btn btn-text btn-danger btn-sm" data-act="delete">删除</button>
                </div>
            </div>`;
        }).join('');
        list.querySelectorAll('.project-item').forEach(item => {
            const id = item.dataset.id;
            const project = projects.find(x => String(x.id) === String(id));
            if (!project) return;
            item.querySelector('[data-act="edit"]').addEventListener('click', () => this.showProjectForm(project));
            item.querySelector('[data-act="sessions"]').addEventListener('click', () => this.showProjectSessions(project));
            item.querySelector('[data-act="archive"]').addEventListener('click', () => this.toggleProjectArchived(project));
            item.querySelector('[data-act="delete"]').addEventListener('click', () => this.deleteProject(project));
        });
    }

    /** 新建/编辑项目表单（名称必填、指令可选）。 */
    showProjectForm(project) {
        const isEdit = !!project;
        const name = isEdit ? (project.name || '') : '';
        const instructions = isEdit ? (project.instructions || '') : '';
        this.showModal(isEdit ? 'Edit Project' : 'New Project', `
            <div class="form-group">
                <label>Name</label>
                <input id="projName" type="text" class="form-control" value="${this.escapeAttr(name)}" maxlength="100">
            </div>
            <div class="form-group">
                <label>Instructions（仅本项目会话生效；与全局安全限制冲突时以后者为准）</label>
                <textarea id="projInstructions" class="form-control" rows="6" style="width:100%;resize:vertical;">${this.escapeHtml(instructions)}</textarea>
            </div>
        `, async () => {
            const n = document.getElementById('projName').value.trim();
            if (!n) { this.showToast('名称不能为空', 'error'); return; }
            const ins = document.getElementById('projInstructions').value.trim();
            const url = isEdit ? `/api/projects/${encodeURIComponent(project.id)}` : '/api/projects';
            const method = isEdit ? 'PUT' : 'POST';
            const resp = await this.authFetch(url, {
                method,
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: n, instructions: ins })
            });
            if (resp.ok) {
                this.showToast(isEdit ? '项目已更新' : '项目已创建', 'success');
                this.loadProjects();
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('保存失败 (' + resp.status + ')'), 'error');
            }
        });
        document.getElementById('modalConfirm').textContent = 'Save';
        document.getElementById('modalConfirm').style.display = 'block';
    }

    /** 归档开关（归档不删除任何资源）。 */
    async toggleProjectArchived(project) {
        try {
            const resp = await this.authFetch(`/api/projects/${encodeURIComponent(project.id)}`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ archived: !project.archived })
            });
            if (resp.ok) {
                this.loadProjects();
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('操作失败 (' + resp.status + ')'), 'error');
            }
        } catch (e) {
            this.showToast('网络错误', 'error');
        }
    }

    /**
     * 删除项目：先展示影响（归属会话数/资料引用数），确认后才删。
     * 删除只解除引用：附件物理文件与会话转录均保留。
     */
    async deleteProject(project) {
        try {
            // 先查归属会话数作为影响提示
            const sessResp = await this.authFetch('/api/sessions?projectId='
                + encodeURIComponent(project.id));
            let sessionCount = 0;
            if (sessResp.ok) {
                const arr = await sessResp.json();
                sessionCount = Array.isArray(arr) ? arr.length : 0;
            }
            const ok = confirm(`删除项目「${project.name}」？\n\n影响：\n- 归属会话 ${sessionCount} 个（转录保留，仅不再注入项目指令）\n- 资料引用 ${project.resourceCount} 个（附件文件保留）\n\n此操作不可撤销。`);
            if (!ok) return;
            const resp = await this.authFetch(
                `/api/projects/${encodeURIComponent(project.id)}?confirm=true`, { method: 'DELETE' });
            if (resp.ok) {
                const result = await resp.json();
                this.showToast(`项目已删除（解除资料引用 ${result.releasedResources || 0} 个；附件与会话保留）`, 'success');
                this.loadProjects();
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('删除失败 (' + resp.status + ')'), 'error');
            }
        } catch (e) {
            this.showToast('网络错误，删除失败', 'error');
        }
    }

    /**
     * 查看项目归属会话（服务端按 projectId 过滤）；提供切换入口。
     */
    async showProjectSessions(project) {
        try {
            const resp = await this.authFetch('/api/sessions?projectId='
                + encodeURIComponent(project.id));
            if (!resp.ok) { this.showToast('查询归属会话失败 (' + resp.status + ')', 'error'); return; }
            const sessions = await resp.json();
            const items = (sessions || [])
                .filter(s => s.key && s.key.startsWith('web:'))
                .map(s => {
                    const title = s.displayTitle || this.extractChatTitle(s.key, s.firstMessage);
                    return `<div class="chat-history-item" data-key="${this.escapeAttr(s.key)}">
                        <span class="history-title">${this.escapeHtml(title)}</span>
                        <span class="project-meta">${s.messageCount || 0} 条 · ${this.timeAgo(Date.parse(s.updated))}</span>
                    </div>`;
                }).join('');
            const body = items
                ? `<p class="form-hint">点击切换到该会话（项目指令与项目记忆域随会话归属注入）。</p><div class="project-session-list">${items}</div>`
                : '<p class="empty-state">该项目还没有归属会话。新建会话时可通过会话菜单（⋯ → 6）设置归属。</p>';
            this.showModal(`项目会话 · ${this.escapeHtml(project.name)}`, body, null);
            document.getElementById('modalConfirm').style.display = 'none';
            document.querySelectorAll('.project-session-list .chat-history-item').forEach(item => {
                item.addEventListener('click', () => {
                    this.hideModal();
                    this.navigateTo('chat');
                    this.switchChatSession(item.dataset.key);
                });
            });
        } catch (e) {
            this.showToast('查询归属会话失败：' + e.message, 'error');
        }
    }

    // ==================== Trace（单次执行时间线） ====================

    /**
     * 打开某会话的执行 Trace 时间线：按转录顺序还原 user/thinking/tool/assistant 各步骤，
     * 工具步骤带时间戳与相对上一步的耗时。纯前端复用已持久化的会话详情数据。
     */
    async showSessionTrace(sessionId) {
        try {
            const resp = await this.authFetch(`/api/sessions/${encodeURIComponent(sessionId)}`);
            if (!resp.ok) { this.showToast('加载 Trace 失败 (' + resp.status + ')', 'error'); return; }
            const messages = await resp.json();
            this.showModal(`执行 Trace · ${sessionId}`, this.renderTraceTimeline(messages || []), null);
            document.getElementById('modalConfirm').style.display = 'none';
        } catch (e) {
            this.showToast('加载 Trace 失败：' + e.message, 'error');
        }
    }

    /**
     * 将会话转录渲染为垂直时间线 HTML。
     */
    renderTraceTimeline(messages) {
        if (!messages.length) return '<p class="empty-state">该会话没有可展示的执行步骤</p>';
        let prevTs = null;
        const nodes = [];
        const fmtTime = (iso) => {
            const t = Date.parse(iso);
            return isNaN(t) ? '' : new Date(t).toLocaleTimeString();
        };
        for (const msg of messages) {
            if (msg.role === 'summary') {
                nodes.push(`<div class="trace-node trace-summary"><div class="trace-dot">📋</div><div class="trace-body"><div class="trace-label">上文已压缩为摘要</div></div></div>`);
                continue;
            }
            if (msg.role === 'user') {
                nodes.push(`<div class="trace-node trace-user"><div class="trace-dot">👤</div><div class="trace-body"><div class="trace-label">用户输入</div><div class="trace-text">${this.escapeHtml((msg.content || '').slice(0, 400))}</div></div></div>`);
                continue;
            }
            if (msg.role === 'assistant') {
                if (msg.thinking) {
                    nodes.push(`<div class="trace-node trace-thinking"><div class="trace-dot">💭</div><div class="trace-body"><div class="trace-label">思考</div><details class="trace-details"><summary>展开</summary><div class="trace-text">${this.escapeHtml(msg.thinking)}</div></details></div></div>`);
                }
                const records = Array.isArray(msg.toolCallRecords) ? msg.toolCallRecords : [];
                for (const r of records) {
                    const ts = r.timestamp || null;
                    let delta = '';
                    if (ts && prevTs) {
                        const dt = Date.parse(ts) - prevTs;
                        if (!isNaN(dt) && dt >= 0) delta = ` +${(dt / 1000).toFixed(1)}s`;
                    }
                    if (ts) prevTs = Date.parse(ts);
                    const okCls = r.success ? 'trace-ok' : 'trace-fail';
                    const okIcon = r.success ? '✅' : '❌';
                    nodes.push(`<div class="trace-node trace-tool ${okCls}"><div class="trace-dot">🔧</div><div class="trace-body">`
                        + `<div class="trace-label">${this.escapeHtml(r.toolName || 'tool')} ${okIcon}<span class="trace-time">${ts ? this.escapeHtml(fmtTime(ts) + delta) : ''}</span></div>`
                        + (r.argsSummary ? `<details class="trace-details"><summary>参数</summary><pre class="trace-pre">${this.escapeHtml(r.argsSummary)}</pre></details>` : '')
                        + (r.resultSummary ? `<details class="trace-details"><summary>结果</summary><pre class="trace-pre">${this.escapeHtml(r.resultSummary)}</pre></details>` : '')
                        + `</div></div>`);
                }
                if (msg.content) {
                    nodes.push(`<div class="trace-node trace-assistant"><div class="trace-dot">🤖</div><div class="trace-body"><div class="trace-label">助手回复</div><details class="trace-details"><summary>展开</summary><div class="trace-text">${this.escapeHtml((msg.content || '').slice(0, 2000))}</div></details></div></div>`);
                }
            }
        }
        return `<div class="trace-timeline">${nodes.join('')}</div>`;
    }

    /**
     * 判断消息内容是否为后台系统通知（如 subagent 异步任务完成回流的消息）。
     * 这类消息由后端以 `[System: <sender>] ...` 前缀写入会话历史（角色为 user，用于触发 LLM），
     * 前端据此将其按模型输出样式展示，避免误显示为用户输入。
     */
    isSystemNoticeContent(content) {
        return typeof content === 'string' && /^\s*\[System:\s/.test(content);
    }

    // ==================== Channels ====================

    async loadChannels() {
        try {
            const response = await this.authFetch('/api/channels');
            const channels = await response.json();
            
            const grid = document.getElementById('channelsGrid');
            grid.innerHTML = channels.map(ch => {
                const stateBadge = !ch.enabled
                    ? ''
                    : ch.state
                        ? `<span class="badge ${this.channelStateBadge(ch.state)}">${ch.state}</span>`
                        : `<span class="badge ${ch.running ? 'badge-success' : 'badge-disabled'}">${ch.running ? 'Running' : 'Stopped'}</span>`;
                return `
                <div class="card" data-channel="${ch.name}">
                    <div class="card-header">
                        <span class="badge ${ch.enabled ? 'badge-success' : 'badge-disabled'}">
                            ${ch.enabled ? 'Enabled' : 'Disabled'}
                        </span>
                        ${stateBadge}
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
                `;
            }).join('');
        } catch (error) {
            console.error('Failed to load channels:', error);
        }
    }

    channelStateBadge(state) {
        if (state === 'usable') return 'badge-success';
        if (state === 'recovering') return 'badge-timeout';
        return 'badge-error';
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
                messageCount: s.messageCount,
                owner: s.owner || '',
                visibility: s.visibility || '',
                progress: s.progress || null
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
            tbody.innerHTML = '<tr><td colspan="8" class="empty-state">No sessions found</td></tr>';
            return;
        }
        
        tbody.innerHTML = pageSessions.map(s => `
            <tr data-session-key="${this.escapeHtml(s.sessionId)}">
                <td class="col-checkbox">
                    <input type="checkbox" class="session-checkbox" value="${this.escapeHtml(s.sessionId)}">
                </td>
                <td class="col-id">${this.escapeHtml(s.id)}</td>
                <td class="col-name">${this.escapeHtml(s.name)}${this.renderProgressChip(s.progress)}</td>
                <td class="col-session-id">${this.escapeHtml(s.sessionId)}</td>
                <td class="col-user-id">${this.escapeHtml(s.userId)}</td>
                <td class="col-owner">${this.renderOwnerCell(s.owner)}</td>
                <td class="col-visibility">${this.renderVisibilityCell(s.visibility)}</td>
                <td class="col-action">
                    <div class="action-buttons">
                        <button class="btn-edit" onclick="app.showSessionTrace('${this.escapeHtml(s.sessionId)}')">Trace</button>
                        <button class="btn-edit" onclick="app.viewSessionDetail('${this.escapeHtml(s.sessionId)}')">Edit</button>
                        <button class="btn-delete" onclick="app.deleteSession('${this.escapeHtml(s.sessionId)}')">Delete</button>
                    </div>
                </td>
            </tr>
        `).join('');
    }

    /**
     * 渲染归属人单元格。
     *
     * <p>owner 形如 {@code u:<channel>:<senderId>}，展示时去掉 {@code u:} 前缀——
     * 前缀是为了与聊天域区分命名空间，对看表格的人无意义。</p>
     * <p>空 owner 是本特性上线前的历史会话，明示为 Legacy 而不是留白，
     * 否则用户会以为是数据没加载出来。</p>
     */
    renderOwnerCell(owner) {
        if (!owner) {
            return '<span class="badge-legacy" title="本特性上线前的会话，对所有人可见">Legacy</span>';
        }
        const display = owner.startsWith('u:') ? owner.substring(2) : owner;
        return `<span class="owner-tag" title="${this.escapeHtml(owner)}">${this.escapeHtml(display)}</span>`;
    }

    renderVisibilityCell(visibility) {
        if (visibility === 'SHARED') {
            return '<span class="badge-shared">Shared</span>';
        }
        if (visibility === 'PRIVATE') {
            return '<span class="badge-private">Private</span>';
        }
        return '<span class="badge-legacy">—</span>';
    }

    /**
     * 会话正在跑时在名称后面跟一个阶段小标
     */
    renderProgressChip(progress) {
        if (!progress || !progress.phase) return '';
        const steps = progress.hasKnownTotal
            ? ` ${progress.completedSteps}/${progress.totalSteps}`
            : '';
        return `<span class="progress-chip" title="${this.escapeHtml(progress.detail || '')}">`
            + `<span class="progress-chip-dot"></span>${this.escapeHtml(progress.phase)}${steps}</span>`;
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
            this.cronJobs = jobs;
            
            const list = document.getElementById('cronList');
            if (jobs.length === 0) {
                list.innerHTML = '<p class="empty-state">No cron jobs configured</p>';
                return;
            }
            
            list.innerHTML = jobs.map(job => {
                const lastBadge = job.lastStatus
                    ? `<span class="badge ${this.cronStatusBadge(job.lastStatus)}">${job.lastStatus}</span>` : '';
                // P6：执行模式徽标（继续同会话模式明示，避免误以为后台默默追加当前会话）
                const runModeBadge = job.runMode === 'CONTINUE_SESSION'
                    ? '<span class="badge badge-outline" title="继续同一专用会话（忙碌时跳过本轮）">继续会话</span>' : '';
                const history = job.history || [];
                const historyRows = history.map(r => {
                    // P6：会话跳转与成果徽标（生成成功≠消息已送达，分开展示）
                    const sessionLink = r.sessionKey
                        ? `<button class="cron-history-session" title="查看执行会话" data-session="${this.escapeAttr(r.sessionKey)}">${this.escapeHtml(r.sessionKey)}</button>`
                        : '';
                    const artifactBadges = (r.artifactIds || []).map(aid =>
                        `<button class="cron-history-artifact" title="预览成果 ${this.escapeAttr(aid)}" data-artifact="${this.escapeAttr(aid)}">🗂 ${this.escapeHtml(aid)}</button>`).join('');
                    const deliveryBadge = r.deliveryStatus
                        ? `<span class="badge ${r.deliveryStatus === 'generated' ? 'badge-outline' : 'badge-disabled'}" title="生成成功≠消息已送达：投递由通道异步承担，需通道回执确认">投递:${r.deliveryStatus}</span>`
                        : '';
                    return `
                    <div class="cron-history-row">
                        <span class="cron-history-time">${this.formatDateTime(r.startedAtMs)}</span>
                        <span class="badge ${this.cronStatusBadge(r.status)}">${r.status}</span>
                        <span class="cron-history-trigger">${r.trigger}</span>
                        <span class="cron-history-duration">${(r.durationMs / 1000).toFixed(1)}s</span>
                        ${deliveryBadge}${sessionLink}${artifactBadges}
                        <span class="cron-history-error" title="${this.escapeHtml(r.error || '')}">${this.escapeHtml(r.error || '')}</span>
                    </div>`;
                }).join('');
                return `
                <div class="cron-entry">
                    <div class="cron-item">
                        <div class="cron-info">
                            <div class="cron-name">${this.escapeHtml(job.name)} ${lastBadge}${runModeBadge}</div>
                            <div class="cron-meta">${this.escapeHtml(job.schedule)}${job.tz ? ' (' + this.escapeHtml(job.tz) + ')' : ''} • ${this.escapeHtml((job.message || '').substring(0, 50))}... • last run: ${job.lastRun ? this.formatDateTime(job.lastRun) : 'never'}${job.lastError ? ' • ' + this.escapeHtml(job.lastError) : ''}</div>
                        </div>
                        <span class="badge ${job.enabled ? 'badge-success' : 'badge-disabled'}">${job.enabled ? 'Enabled' : 'Disabled'}</span>
                        <div class="cron-actions">
                            <button class="btn btn-secondary btn-sm" onclick="app.editCronJob('${job.id}')">Edit</button>
                            <button class="btn btn-secondary btn-sm" onclick="app.runCronJob('${job.id}')" title="手动重试会产生新的执行实例">Run</button>
                            <button class="btn btn-secondary btn-sm" onclick="app.toggleCronHistory('${job.id}')">History (${history.length})</button>
                            <button class="btn btn-secondary btn-sm" onclick="app.toggleCronJob('${job.id}', ${!job.enabled})">${job.enabled ? 'Disable' : 'Enable'}</button>
                            <button class="btn btn-secondary btn-sm" onclick="app.deleteCronJob('${job.id}')">Delete</button>
                        </div>
                    </div>
                    <div class="cron-history" id="cronHistory-${job.id}" style="display: none;">
                        ${historyRows || '<p class="empty-state">No runs yet</p>'}
                    </div>
                </div>`;
            }).join('');
            // P6：历史行交互——会话跳转 / 成果预览（事件委托，避免 id 拼接）
            list.querySelectorAll('.cron-history-session').forEach(btn => {
                btn.addEventListener('click', () => this.openCronRunSession(btn.dataset.session));
            });
            list.querySelectorAll('.cron-history-artifact').forEach(btn => {
                btn.addEventListener('click', () => this.previewArtifactById(btn.dataset.artifact));
            });
        } catch (error) {
            console.error('Failed to load cron jobs:', error);
        }

    }

    showAddCronModal() {
        this.showModal('Add Cron Job', this.buildCronFormHtml('cron', null), async () => {
            const data = this.readCronForm('cron', null);
            if (!data) return;
            const resp = await this.authFetch('/api/cron', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('创建失败 (' + resp.status + ')'), 'error');
                return;
            }
            this.hideModal();
            this.loadCronJobs();
        });
        this.bindCronForm('cron');
    }

    // ==================== P6：Cron 表单共享构建器（模板/时区/预览/执行模式） ====================

    /**
     * 构建 cron 表单 HTML（新建/编辑/从会话创建共用）。
     * prefix 用于隔离 DOM id；job 非 null 时回填既有值（编辑/从会话预填）。
     */
    buildCronFormHtml(prefix, job) {
        const isEvery = job ? job.kind === 'every' : false;
        const isAt = job ? job.kind === 'at' : false;
        const isCron = job ? job.kind === 'cron' : !isEvery && !isAt;
        const name = job ? (job.name || '') : '';
        const message = job ? (job.message || '') : (this._lastUserMessage || '');
        const outputTarget = job ? (job.outputTarget || '') : '';
        const runMode = job && job.runMode === 'CONTINUE_SESSION' ? 'CONTINUE_SESSION' : 'NEW_SESSION';
        const tz = job ? (job.tz || '') : Intl.DateTimeFormat().resolvedOptions().timeZone || '';
        const everySeconds = job && job.everyMs ? Math.round(job.everyMs / 1000) : 3600;
        const expr = job ? (job.expr || '') : '';
        const atLocal = '';
        const channel = job ? (job.channel || '') : '';
        const to = job ? (job.to || '') : '';
        return `
            <div class="form-group">
                <label>Name</label>
                <input class="form-control" id="${prefix}Name" placeholder="Job name" value="${this.escapeAttr(name)}">
            </div>
            <div class="form-group">
                <label>Message（任务指令）</label>
                <textarea class="form-control" id="${prefix}Message" rows="3" placeholder="Task message for agent">${this.escapeHtml(message)}</textarea>
            </div>
            <div class="form-group">
                <label>输出要求（可选，随任务注入）</label>
                <textarea class="form-control" id="${prefix}OutputTarget" rows="2" placeholder="e.g. 生成日报 Markdown 并总结 3 个要点">${this.escapeHtml(outputTarget)}</textarea>
            </div>
            <div class="form-group">
                <label>模板（快捷填充 cron 表达式）</label>
                <select class="form-control" id="${prefix}Template">
                    <option value="">自定义…</option>
                    <option value="0 9 * * *">每日 9 点</option>
                    <option value="0 9 * * 1">每周一 9 点</option>
                    <option value="0 9 * * 1-5">工作日 9 点</option>
                </select>
            </div>
            <div class="form-group">
                <label>Schedule Type</label>
                <select class="form-control" id="${prefix}Type">
                    <option value="cron" ${isCron ? 'selected' : ''}>Cron expression</option>
                    <option value="every" ${isEvery ? 'selected' : ''}>Every X seconds</option>
                    <option value="at" ${isAt ? 'selected' : ''}>At once（一次性）</option>
                </select>
            </div>
            <div class="form-group" id="${prefix}EveryGroup" style="${isEvery ? '' : 'display:none;'}">
                <label>Interval (seconds)</label>
                <input class="form-control" id="${prefix}Every" type="number" value="${everySeconds}">
            </div>
            <div class="form-group" id="${prefix}ExprGroup" style="${isCron ? '' : 'display:none;'}">
                <label>Cron Expression</label>
                <input class="form-control" id="${prefix}Expr" placeholder="0 8 * * *" value="${this.escapeAttr(expr)}">
            </div>
            <div class="form-group" id="${prefix}AtGroup" style="${isAt ? '' : 'display:none;'}">
                <label>执行时间（本地时区，一次性）</label>
                <input class="form-control" id="${prefix}AtLocal" type="datetime-local" value="${this.escapeAttr(atLocal)}">
            </div>
            <div class="form-group">
                <label>时区（cron 按此时区计算；留空用服务器默认）</label>
                <input class="form-control" id="${prefix}Tz" placeholder="e.g. Asia/Shanghai" value="${this.escapeAttr(tz)}">
            </div>
            <div class="form-group">
                <button type="button" class="btn btn-secondary btn-sm" id="${prefix}PreviewBtn">⏱ 预览未来 5 次执行</button>
                <div id="${prefix}PreviewResult" class="cron-preview-result"></div>
            </div>
            <div class="form-group">
                <label>执行模式</label>
                <select class="form-control" id="${prefix}RunMode">
                    <option value="NEW_SESSION" ${runMode === 'NEW_SESSION' ? 'selected' : ''}>每次新会话（默认，只带确认的资料与指令）</option>
                    <option value="CONTINUE_SESSION" ${runMode === 'CONTINUE_SESSION' ? 'selected' : ''}>继续同一专用会话（忙碌时跳过本轮）</option>
                </select>
            </div>
            <div class="form-group">
                <label>Channel (optional)</label>
                <input class="form-control" id="${prefix}Channel" placeholder="e.g. dingtalk, telegram" value="${this.escapeAttr(channel)}">
            </div>
            <div class="form-group">
                <label>To / Chat ID (optional)</label>
                <input class="form-control" id="${prefix}To" placeholder="Target chat ID" value="${this.escapeAttr(to)}">
            </div>`;
    }

    /** 绑定表单交互：类型切换 / 模板填充 / 预览按钮。 */
    bindCronForm(prefix) {
        const typeSelect = document.getElementById(`${prefix}Type`);
        if (typeSelect) {
            typeSelect.onchange = () => {
                document.getElementById(`${prefix}EveryGroup`).style.display = typeSelect.value === 'every' ? '' : 'none';
                document.getElementById(`${prefix}ExprGroup`).style.display = typeSelect.value === 'cron' ? '' : 'none';
                document.getElementById(`${prefix}AtGroup`).style.display = typeSelect.value === 'at' ? '' : 'none';
            };
        }
        const template = document.getElementById(`${prefix}Template`);
        if (template) {
            template.onchange = () => {
                if (template.value) {
                    const type = document.getElementById(`${prefix}Type`);
                    type.value = 'cron';
                    document.getElementById(`${prefix}Expr`).value = template.value;
                    type.onchange();
                }
            };
        }
        const previewBtn = document.getElementById(`${prefix}PreviewBtn`);
        if (previewBtn) {
            previewBtn.onclick = () => this.previewCronSchedule(prefix);
        }
    }

    /** 读取表单调度部分（不含 name/message 等）。 */
    readCronSchedule(prefix) {
        const type = document.getElementById(`${prefix}Type`).value;
        const tz = document.getElementById(`${prefix}Tz`).value.trim();
        const data = { kind: type };
        if (type === 'every') {
            const seconds = parseInt(document.getElementById(`${prefix}Every`).value, 10);
            if (!seconds || seconds <= 0) {
                this.showToast('间隔必须为正数（秒）', 'error');
                return null;
            }
            data.everySeconds = seconds;
        } else if (type === 'at') {
            const atLocal = document.getElementById(`${prefix}AtLocal`).value;
            const atMs = atLocal ? Date.parse(atLocal) : NaN;
            if (isNaN(atMs) || atMs <= Date.now()) {
                this.showToast('一次性执行时间必须在未来', 'error');
                return null;
            }
            data.atMs = atMs;
        } else {
            const expr = document.getElementById(`${prefix}Expr`).value.trim();
            if (!expr) {
                this.showToast('cron 表达式不能为空', 'error');
                return null;
            }
            data.cron = expr;
        }
        if (tz) data.tz = tz;
        return data;
    }

    /** 调服务端预览未来 5 次执行（复用调度计算，前端不另写解释器）。 */
    async previewCronSchedule(prefix) {
        const resultDiv = document.getElementById(`${prefix}PreviewResult`);
        const schedule = this.readCronSchedule(prefix);
        if (!schedule) return;
        resultDiv.innerHTML = '<span class="form-hint">计算中…</span>';
        try {
            const resp = await this.authFetch('/api/cron/preview', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(schedule)
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                resultDiv.innerHTML = `<span class="cron-preview-error">${this.escapeHtml(err.error || ('预览失败 (' + resp.status + ')'))}</span>`;
                return;
            }
            const data = await resp.json();
            const items = (data.nextRuns || []).map(ts =>
                `<li>${this.escapeHtml(new Date(ts).toLocaleString())}</li>`).join('');
            resultDiv.innerHTML = items
                ? `<ul class="cron-preview-list">${items}</ul>`
                : '<span class="cron-preview-error">无未来执行点（检查表达式或时间）</span>';
        } catch (e) {
            resultDiv.innerHTML = `<span class="cron-preview-error">预览失败：${this.escapeHtml(e.message)}</span>`;
        }
    }

    /** 读取完整表单（schedule + 名称/消息/输出要求/执行模式/通道）。 */
    readCronForm(prefix, job) {
        const schedule = this.readCronSchedule(prefix);
        if (!schedule) return null;
        const name = document.getElementById(`${prefix}Name`).value.trim();
        const message = document.getElementById(`${prefix}Message`).value.trim();
        if (!name) { this.showToast('名称不能为空', 'error'); return null; }
        if (!message) { this.showToast('任务指令不能为空', 'error'); return null; }
        const data = { name, message, ...schedule };
        const outputTarget = document.getElementById(`${prefix}OutputTarget`).value.trim();
        if (outputTarget) data.outputTarget = outputTarget;
        data.runMode = document.getElementById(`${prefix}RunMode`).value;
        const channel = document.getElementById(`${prefix}Channel`).value.trim();
        const to = document.getElementById(`${prefix}To`).value.trim();
        if (channel) data.channel = channel;
        if (to) data.to = to;
        // 编辑/从会话创建时回传既有 P6 字段
        if (job && job.projectId) data.projectId = job.projectId;
        if (job && job.attachmentIds) data.attachmentIds = job.attachmentIds;
        return data;
    }

    /**
     * P6：从当前会话创建周期任务——任务描述预填本轮用户消息，可勾选当前会话项目资料；
     * 用户确认前不创建调度；sourceSessionKey 记录来源会话。
     */
    async showScheduleFromChat() {
        const sourceMessage = this._lastUserMessage || '';
        if (!sourceMessage) {
            this.showToast('先发送一次消息，再把它设为周期任务', 'info');
            return;
        }
        // 已选资料：当前会话归属项目的资料引用（仅显式确认的资料会随任务复用）
        let project = null;
        let projectResources = [];
        try {
            const sessResp = await this.authFetch('/api/sessions');
            if (sessResp.ok) {
                const sessions = await sessResp.json();
                const current = (sessions || []).find(s => s.key === this.chatSessionId);
                const pid = current && current.projectId;
                if (pid) {
                    const projResp = await this.authFetch(`/api/projects/${encodeURIComponent(pid)}`);
                    if (projResp.ok) {
                        project = await projResp.json();
                        projectResources = (project.resources || []).filter(r => !r.missing);
                    }
                }
            }
        } catch (e) { /* 项目能力未启用时无资料可选 */ }
        const resourceChecks = projectResources.length
            ? `<div class="form-group"><label>已选资料（仅勾选的资料会随任务复用）</label>
                ${projectResources.map(r => `
                    <label class="cron-resource-check">
                        <input type="checkbox" class="cron-resource-item" value="${this.escapeAttr(r.attachmentId)}" checked>
                        ${this.escapeHtml(r.name || r.attachmentId)}
                        <span class="form-hint">（${r.status || ''}${r.parsedChars ? ' · ' + r.parsedChars + ' 字' : ''}）</span>
                    </label>`).join('')}
               </div>`
            : '<p class="form-hint">当前会话未归属项目或项目无资料——任务将只携带任务指令。</p>';
        const suggestedName = (sourceMessage.length > 30 ? sourceMessage.substring(0, 30) + '…' : sourceMessage);
        this.showModal('设为周期任务', `
            <p class="form-hint">把本轮对话转成周期任务：每次运行新建会话（默认），只携带下方确认的任务指令与勾选资料，不复制聊天历史。</p>
            ${this.buildCronFormHtml('fromChat', { name: suggestedName, message: sourceMessage, kind: 'cron', tz: Intl.DateTimeFormat().resolvedOptions().timeZone })}
            ${resourceChecks}
        `, async () => {
            const data = this.readCronForm('fromChat', project ? { projectId: project.id } : null);
            if (!data) return;
            data.sourceSessionKey = this.chatSessionId;
            const checked = [...document.querySelectorAll('.cron-resource-item:checked')].map(el => el.value);
            if (checked.length) data.attachmentIds = checked;
            const resp = await this.authFetch('/api/cron', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('创建失败 (' + resp.status + ')'), 'error');
                return;
            }
            const result = await resp.json();
            this.hideModal();
            this.showToast(`周期任务已创建（下次执行：${result.nextRun ? new Date(result.nextRun).toLocaleString() : '待计算'}）`, 'success');
        });
        this.bindCronForm('fromChat');
    }

    /**
     * P6：跳转查看 cron 执行会话——cron 专用会话（cron-* key）不在聊天侧栏中，
     * 以 Trace 时间线弹窗展示执行过程；会话已清理时明确提示。
     */
    async openCronRunSession(sessionKey) {
        if (!sessionKey) return;
        try {
            const resp = await this.authFetch(`/api/sessions/${encodeURIComponent(sessionKey)}`);
            if (resp.status === 404) {
                this.showToast('执行会话已不存在（可能已被清理）', 'info');
                return;
            }
            if (!resp.ok) {
                this.showToast('加载执行会话失败 (' + resp.status + ')', 'error');
                return;
            }
            const messages = await resp.json();
            this.showModal(`Cron 执行会话 · ${sessionKey}`, this.renderTraceTimeline(messages || []), null);
            document.getElementById('modalConfirm').style.display = 'none';
        } catch (e) {
            this.showToast('加载执行会话失败：' + e.message, 'error');
        }
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

    async runCronJob(id) {
        try {
            await this.authFetch(`/api/cron/${id}/run`, { method: 'POST' });
            // 任务异步执行，稍延迟刷新列表以拿到执行历史
            setTimeout(() => this.loadCronJobs(), 1000);
        } catch (error) {
            console.error('Failed to run cron job:', error);
        }
    }

    editCronJob(id) {
        const job = (this.cronJobs || []).find(j => j.id === id);
        if (!job) return;
        this.showModal('Edit Cron Job', this.buildCronFormHtml('editCron', job), async () => {
            const data = this.readCronForm('editCron', job);
            if (!data) return;
            const resp = await this.authFetch(`/api/cron/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('更新失败 (' + resp.status + ')'), 'error');
                return;
            }
            this.hideModal();
            this.loadCronJobs();
        });
        this.bindCronForm('editCron');
    }

    toggleCronHistory(id) {
        const el = document.getElementById('cronHistory-' + id);
        if (el) {
            el.style.display = el.style.display === 'none' ? 'block' : 'none';
        }
    }

    cronStatusBadge(status) {
        if (status === 'ok') return 'badge-success';
        if (status === 'timeout') return 'badge-timeout';
        return 'badge-error';
    }

    formatDateTime(ms) {
        return new Date(ms).toLocaleString([], {
            month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit'
        });
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
                        <button class="btn btn-text" onclick="app.testProvider('${p.name}')" ${p.authorized ? '' : 'disabled'}>🔌 Test</button>
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
        
        // 保存前验证连接
        document.getElementById('testModelBtn').onclick = () => this.testCurrentModel();
        
        // 初始化按钮状态
        checkChanges();
    }

    async testProvider(name) {
        await this.testModelConnection({ provider: name });
    }

    async testCurrentModel() {
        const provider = document.getElementById('providerSelect').value;
        const model = document.getElementById('modelSelect').value;
        if (!provider) {
            alert('Please select a provider first');
            return;
        }
        await this.testModelConnection({ provider, model });
    }

    /**
     * 调用 /api/models/test 验证 provider/模型连接并反馈结果。
     */
    async testModelConnection(payload) {
        try {
            const response = await this.authFetch('/api/models/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const result = await response.json();
            if (result.success) {
                alert(`✓ ${result.model} connected in ${result.latencyMs}ms`);
            } else {
                alert(`✗ ${result.model || payload.provider} failed: ${result.error || 'unknown error'}`);
            }
        } catch (error) {
            alert('Test failed: ' + error.message);
        }
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
            document.getElementById('cfgHeartbeatInterval').value = config.heartbeatIntervalSeconds;
            document.getElementById('cfgHeartbeatTimeout').value = config.heartbeatTimeoutSeconds;
            document.getElementById('cfgRestrictToWorkspace').value = config.restrictToWorkspace.toString();
            // 后端未返回时默认开启思考模式
            document.getElementById('cfgThinkingEnabled').value = (config.thinkingEnabled !== false).toString();
        } catch (error) {
            console.error('Failed to load agent config:', error);
        }

        document.getElementById('saveAgentConfigBtn').onclick = async () => {
            const data = {
                maxTokens: parseInt(document.getElementById('cfgMaxTokens').value),
                temperature: parseFloat(document.getElementById('cfgTemperature').value),
                maxToolIterations: parseInt(document.getElementById('cfgMaxToolIterations').value),
                heartbeatEnabled: document.getElementById('cfgHeartbeatEnabled').value === 'true',
                heartbeatIntervalSeconds: parseInt(document.getElementById('cfgHeartbeatInterval').value) || 0,
                heartbeatTimeoutSeconds: parseInt(document.getElementById('cfgHeartbeatTimeout').value) || 0,
                restrictToWorkspace: document.getElementById('cfgRestrictToWorkspace').value === 'true',
                thinkingEnabled: document.getElementById('cfgThinkingEnabled').value === 'true'
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

    /**
     * 转义 HTML/SVG <b>属性值</b>。
     *
     * <p>不能直接用 {@link #escapeHtml}：它基于 {@code innerHTML}，只转义
     * {@code & < >}，<b>不转义引号</b>。而角色名、工作流节点 id 都可能由 LLM 生成，
     * 一个带双引号的名字就能从 {@code data-from="..."} 里逐出去，把整张 SVG 结构弄坏。</p>
     *
     * @param {string} value - 待转义的属性值
     * @returns {string} 可安全放入双引号属性的字符串
     */
    escapeAttr(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
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

    // ==================== Slash Command Menu ====================

    /**
     * 从 API 获取技能列表（带缓存）
     */
    async fetchSkillsForSlash() {
        if (this.skillsCache) return this.skillsCache;
        try {
            const resp = await this.authFetch('/api/skills');
            if (resp.ok) {
                this.skillsCache = await resp.json();
            }
        } catch (e) {
            console.error('Failed to fetch skills for slash menu:', e);
        }
        return this.skillsCache || [];
    }

    /**
     * 处理输入框中的 slash 命令检测
     */
    async handleSlashInput(input) {
        const value = input.value;
        const cursorPos = input.selectionStart;
        const textBeforeCursor = value.substring(0, cursorPos);

        // 只在行首输入 / 时触发
        const lastNewline = textBeforeCursor.lastIndexOf('\n');
        const lineStart = lastNewline + 1;
        const lineText = textBeforeCursor.substring(lineStart);

        if (lineText.startsWith('/') && lineStart === 0) {
            const query = lineText.substring(1).toLowerCase();
            const skills = await this.fetchSkillsForSlash();
            const filtered = query
                ? skills.filter(s =>
                    s.name.toLowerCase().includes(query) ||
                    (s.description && s.description.toLowerCase().includes(query))
                )
                : skills;

            if (filtered.length > 0) {
                this.showSlashMenu(filtered);
            } else {
                this.hideSlashMenu();
            }
        } else {
            this.hideSlashMenu();
        }
    }

    /**
     * 显示 slash 命令菜单
     */
    showSlashMenu(skills) {
        let menu = document.getElementById('slashMenu');
        if (!menu) {
            menu = document.createElement('div');
            menu.id = 'slashMenu';
            menu.className = 'slash-menu';
            document.querySelector('.chat-input-container').appendChild(menu);
        }

        this.slashMenuItems = skills;
        if (this.slashMenuIndex >= skills.length) {
            this.slashMenuIndex = skills.length - 1;
        }

        const sourceIcon = (source) => {
            switch (source) {
                case 'builtin': return '📦';
                case 'global': return '🌐';
                case 'workspace': return '📁';
                default: return '🧩';
            }
        };

        menu.innerHTML = `<div class="slash-menu-header">🧩 Skills</div>`
            + skills.map((s, i) => `
                <div class="slash-menu-item${i === this.slashMenuIndex ? ' active' : ''}"
                     data-name="${this.escapeHtml(s.name)}" data-index="${i}">
                    <div class="slash-menu-item-icon">${sourceIcon(s.source)}</div>
                    <div class="slash-menu-item-info">
                        <div class="slash-menu-item-name">/${this.escapeHtml(s.name)}</div>
                        <div class="slash-menu-item-desc">${this.escapeHtml(s.description || '')}</div>
                    </div>
                    <span class="slash-menu-item-source">${this.escapeHtml(s.source || '')}</span>
                </div>
            `).join('');

        menu.style.display = 'block';
        this.slashMenuVisible = true;

        // 绑定点击和鼠标悬停事件
        menu.querySelectorAll('.slash-menu-item').forEach((item, idx) => {
            item.addEventListener('click', (e) => {
                e.stopPropagation();
                this.selectSlashSkill(item.dataset.name);
            });
            item.addEventListener('mouseenter', () => {
                this.slashMenuIndex = idx;
                this.updateSlashMenuHighlight();
            });
        });
    }

    /**
     * 隐藏 slash 命令菜单
     */
    hideSlashMenu() {
        const menu = document.getElementById('slashMenu');
        if (menu) {
            menu.style.display = 'none';
        }
        this.slashMenuVisible = false;
        this.slashMenuIndex = -1;
        this.slashMenuItems = [];
    }

    /**
     * 处理 slash 菜单的键盘导航
     */
    handleSlashMenuKey(key) {
        if (!this.slashMenuItems || this.slashMenuItems.length === 0) return;

        if (key === 'ArrowDown' || key === 'Tab') {
            this.slashMenuIndex = (this.slashMenuIndex + 1) % this.slashMenuItems.length;
            this.updateSlashMenuHighlight();
        } else if (key === 'ArrowUp') {
            this.slashMenuIndex = this.slashMenuIndex <= 0
                ? this.slashMenuItems.length - 1
                : this.slashMenuIndex - 1;
            this.updateSlashMenuHighlight();
        } else if (key === 'Enter') {
            const idx = this.slashMenuIndex >= 0 ? this.slashMenuIndex : 0;
            this.selectSlashSkill(this.slashMenuItems[idx].name);
        }
    }

    /**
     * 更新 slash 菜单高亮项
     */
    updateSlashMenuHighlight() {
        const menu = document.getElementById('slashMenu');
        if (!menu) return;
        menu.querySelectorAll('.slash-menu-item').forEach((item, i) => {
            item.classList.toggle('active', i === this.slashMenuIndex);
        });
        const activeItem = menu.querySelector('.slash-menu-item.active');
        if (activeItem) activeItem.scrollIntoView({ block: 'nearest' });
    }

    /**
     * 选择一个技能，插入到输入框
     */
    selectSlashSkill(name) {
        const input = document.getElementById('chatInput');
        input.value = '/' + name + ' ';
        input.focus();

        // 将光标移到末尾
        const pos = input.value.length;
        input.setSelectionRange(pos, pos);

        // 自动调整高度
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';

        this.hideSlashMenu();
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

    // ==================== Toast ====================

    /**
     * 轻量级站内提示，替代原生 alert。
     * 右下角堆叠显示，2.6s 后自动淡出移除。
     *
     * @param {string} message - 提示文案
     * @param {'success'|'error'|'info'} [type='info'] - 语义类型，决定配色
     */
    showToast(message, type = 'info') {
        let container = document.getElementById('toastContainer');
        if (!container) {
            container = document.createElement('div');
            container.id = 'toastContainer';
            container.className = 'toast-container';
            document.body.appendChild(container);
        }
        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        toast.textContent = message;
        container.appendChild(toast);
        setTimeout(() => {
            toast.classList.add('toast-hide');
            setTimeout(() => toast.remove(), 300);
        }, 2600);
    }

    // ==================== Dashboard ====================

    /**
     * 加载 Dashboard 总览：并行拉取运行态 / 心跳 / 进化 / 反思 / 通道五个既有端点，
     * 任一失败都不阻塞其余卡片渲染（Promise.allSettled）。
     */
    async loadDashboard() {
        const refreshBtn = document.getElementById('dashRefreshBtn');
        if (refreshBtn) refreshBtn.onclick = () => this.loadDashboard();

        const [chatStatus, heartbeat, feedback, reflection, channels] = await Promise.allSettled([
            this.authFetch('/api/chat/status').then(r => r.ok ? r.json() : null),
            this.authFetch('/api/heartbeat').then(r => r.ok ? r.json() : null),
            this.authFetch('/api/feedback').then(r => r.ok ? r.json() : null),
            this.authFetch('/api/reflection').then(r => r.ok ? r.json() : null),
            this.authFetch('/api/channels').then(r => r.ok ? r.json() : null)
        ]);
        const val = (r) => (r.status === 'fulfilled' ? r.value : null);

        this.renderDashCards(val(chatStatus), val(heartbeat), val(feedback), val(reflection));
        this.renderDashChannels(val(channels));
        this.renderDashHealth(val(reflection));
    }

    /**
     * 渲染 Dashboard 顶部四张状态卡：运行态 / 心跳 / 进化 / 反思。
     */
    renderDashCards(chatStatus, heartbeat, feedback, reflection) {
        const grid = document.getElementById('dashGrid');
        if (!grid) return;

        const running = !!(chatStatus && chatStatus.running);
        const runtimeCard = this.dashCard({
            icon: running ? '🟢' : '⚪',
            title: 'Chat Runtime',
            badge: running ? ['badge-success', 'Running'] : ['badge-disabled', 'Idle'],
            rows: [['Status', this.escapeHtml(running ? 'A task is executing' : 'No active task')]]
        });

        const hbEnabled = !!(heartbeat && heartbeat.enabled);
        const lastRuns = (heartbeat && heartbeat.lastRuns) || {};
        const runKeys = Object.keys(lastRuns);
        const heartbeatCard = this.dashCard({
            icon: '💓',
            title: 'Heartbeat',
            badge: hbEnabled ? ['badge-success', 'Enabled'] : ['badge-disabled', 'Disabled'],
            rows: [
                ['Agents', this.escapeHtml(String(runKeys.length))],
                ['Last Run', this.formatHeartbeatRuns(lastRuns)]
            ],
            action: hbEnabled
                ? `<button class="btn btn-secondary btn-sm" onclick="app.triggerHeartbeat()">Run Now</button>`
                : ''
        });

        const fbEnabled = !!(feedback && feedback.feedbackEnabled);
        const poEnabled = !!(feedback && feedback.promptOptimizationEnabled);
        const evoCard = this.dashCard({
            icon: '🧬',
            title: 'Evolution',
            badge: (fbEnabled || poEnabled) ? ['badge-success', 'Active'] : ['badge-disabled', 'Inactive'],
            rows: [
                ['Feedback', this.escapeHtml(fbEnabled ? 'Enabled' : 'Disabled')],
                ['Prompt Opt.', this.escapeHtml(poEnabled ? 'Enabled' : 'Disabled')]
            ]
        });

        const rfEnabled = !!(reflection && reflection.reflectionEnabled);
        const summary = (reflection && reflection.healthSummary) || [];
        const rfCard = this.dashCard({
            icon: '🩺',
            title: 'Reflection',
            badge: rfEnabled ? ['badge-success', 'Enabled'] : ['badge-disabled', 'Disabled'],
            rows: [
                ['Tracked Tools', this.escapeHtml(String(summary.length))],
                ['', `<a href="#tools-health" class="dash-link" onclick="app.navigateTo('tools-health')">Open Tools Health →</a>`]
            ]
        });

        grid.innerHTML = runtimeCard + heartbeatCard + evoCard + rfCard;
    }

    /**
     * 构造一张 Dashboard 状态卡。rows 为 [label, valueHtml] 数组，value 按已转义的 HTML 注入。
     */
    dashCard({ icon, title, badge, rows, action }) {
        const badgeHtml = badge ? `<span class="badge ${badge[0]}">${this.escapeHtml(badge[1])}</span>` : '';
        const rowsHtml = (rows || []).map(([label, valueHtml]) => `
            <div class="dash-card-row">
                <span class="dash-card-label">${this.escapeHtml(label)}</span>
                <span class="dash-card-value">${valueHtml}</span>
            </div>`).join('');
        return `
            <div class="card dash-card">
                <div class="dash-card-head">
                    <span class="dash-card-icon">${icon}</span>
                    <span class="dash-card-title">${this.escapeHtml(title)}</span>
                    ${badgeHtml}
                </div>
                <div class="dash-card-body">${rowsHtml}</div>
                ${action ? `<div class="dash-card-actions">${action}</div>` : ''}
            </div>`;
    }

    /**
     * 取心跳记录中最近一次（at_ms 最大）的运行做一行摘要。
     */
    formatHeartbeatRuns(lastRuns) {
        let latestKey = null, latestAt = -1;
        for (const [key, info] of Object.entries(lastRuns || {})) {
            const at = (info && info.at_ms) || 0;
            if (at > latestAt) { latestAt = at; latestKey = key; }
        }
        if (!latestKey) return this.escapeHtml('No run recorded yet');
        const info = lastRuns[latestKey] || {};
        const when = latestAt > 0 ? this.timeAgo(latestAt) : 'unknown';
        return `${this.escapeHtml(latestKey)} · ${this.escapeHtml(String(info.status || '?'))} · ${when}`;
    }

    /**
     * 将毫秒时间戳格式化为“N s/m/h/d ago”相对时间。
     */
    timeAgo(ms) {
        const diff = Date.now() - ms;
        if (diff < 0) return 'just now';
        const s = Math.floor(diff / 1000);
        if (s < 60) return s + 's ago';
        const m = Math.floor(s / 60);
        if (m < 60) return m + 'm ago';
        const h = Math.floor(m / 60);
        if (h < 24) return h + 'h ago';
        return Math.floor(h / 24) + 'd ago';
    }

    /**
     * 渲染 Dashboard 通道列表（仅启用项），复用 Channels 页的状态徽章映射。
     */
    renderDashChannels(channels) {
        const box = document.getElementById('dashChannels');
        if (!box) return;
        const enabled = Array.isArray(channels) ? channels.filter(c => c.enabled) : [];
        if (enabled.length === 0) {
            box.innerHTML = '<p class="empty-state">No enabled channels</p>';
            return;
        }
        box.innerHTML = enabled.map(c => {
            const badge = c.state
                ? `<span class="badge ${this.channelStateBadge(c.state)}">${this.escapeHtml(c.state)}</span>`
                : `<span class="badge ${c.running ? 'badge-success' : 'badge-disabled'}">${c.running ? 'Running' : 'Stopped'}</span>`;
            return `<div class="dash-channel">
                <span class="dash-channel-name">${this.escapeHtml(this.capitalize(c.name))}</span>
                ${badge}
            </div>`;
        }).join('');
    }

    /**
     * 渲染 Dashboard 的工具健康 Top 表（取自 reflection.healthSummary）。
     */
    renderDashHealth(reflection) {
        const body = document.getElementById('dashHealthBody');
        if (!body) return;
        const tools = (reflection && Array.isArray(reflection.healthSummary)) ? reflection.healthSummary : [];
        if (tools.length === 0) {
            body.innerHTML = '<tr><td colspan="4" class="empty-state">No tool health data</td></tr>';
            return;
        }
        body.innerHTML = tools.map(t => `
            <tr>
                <td><strong>${this.escapeHtml(t.tool || '')}</strong></td>
                <td>${t.totalCalls || 0}</td>
                <td>${this.renderSuccessRate(t.successRate)}</td>
                <td>${t.p95Ms != null ? t.p95Ms : '—'}</td>
            </tr>`).join('');
    }

    /**
     * 成功率百分比渲染，按阈值着色（≥95% 绿 / ≥80% 黄 / 否则红）。
     */
    renderSuccessRate(rate) {
        if (rate == null) return '—';
        const pct = rate * 100;
        const cls = pct >= 95 ? 'rate-good' : pct >= 80 ? 'rate-warn' : 'rate-bad';
        return `<span class="${cls}">${pct.toFixed(1)}%</span>`;
    }

    /**
     * 手动触发一次心跳，成功后短暂延迟刷新 Dashboard 以反映最新状态。
     */
    async triggerHeartbeat() {
        try {
            const resp = await this.authFetch('/api/heartbeat/now', { method: 'POST' });
            if (resp.ok) {
                this.showToast('Heartbeat triggered', 'success');
                setTimeout(() => this.loadDashboard(), 800);
            } else {
                const err = await resp.json().catch(() => ({}));
                this.showToast(err.error || ('Trigger failed (' + resp.status + ')'), 'error');
            }
        } catch (e) {
            this.showToast('Network error while triggering heartbeat', 'error');
        }
    }

    // ==================== Tools Health (Reflection 2.0) ====================

    /**
     * 加载 Tools Health 页：总览开关 + 工具健康表 + 修复提案列表。
     * Reflection 未启用时展示引导文案，不渲染表格。
     */
    async loadReflection() {
        const refreshBtn = document.getElementById('reflectionRefreshBtn');
        if (refreshBtn) refreshBtn.onclick = () => this.loadReflection();

        let overview = null;
        try {
            const resp = await this.authFetch('/api/reflection');
            overview = resp.ok ? await resp.json() : null;
        } catch (e) {
            console.error('Failed to load reflection overview:', e);
        }

        const disabledBox = document.getElementById('reflectionDisabled');
        const contentBox = document.getElementById('reflectionContent');
        if (!disabledBox || !contentBox) return;

        if (!overview || !overview.reflectionEnabled) {
            disabledBox.textContent = (overview && overview.message)
                || 'Reflection 2.0 is not enabled. Set evolution.reflection.enabled=true in config.';
            disabledBox.style.display = 'block';
            contentBox.style.display = 'none';
            return;
        }
        disabledBox.style.display = 'none';
        contentBox.style.display = 'block';

        const [health, proposals] = await Promise.allSettled([
            this.authFetch('/api/reflection/health').then(r => r.ok ? r.json() : null),
            this.authFetch('/api/reflection/proposals').then(r => r.ok ? r.json() : null)
        ]);
        const val = (r) => (r.status === 'fulfilled' ? r.value : null);
        this.renderHealthTable(val(health));
        this.renderProposals(val(proposals));
    }

    /**
     * 渲染完整工具健康表（8 列：调用/成功/失败/成功率/P50/P95/P99）。
     */
    renderHealthTable(health) {
        const body = document.getElementById('healthTableBody');
        if (!body) return;
        const tools = (health && Array.isArray(health.tools)) ? health.tools : [];
        if (tools.length === 0) {
            body.innerHTML = '<tr><td colspan="8" class="empty-state">No tool health data in window</td></tr>';
            return;
        }
        body.innerHTML = tools.map(t => `
            <tr>
                <td><strong>${this.escapeHtml(t.tool || '')}</strong></td>
                <td>${t.totalCalls || 0}</td>
                <td>${t.successCalls || 0}</td>
                <td>${t.failureCalls || 0}</td>
                <td>${this.renderSuccessRate(t.successRate)}</td>
                <td>${t.p50Ms != null ? t.p50Ms : '—'}</td>
                <td>${t.p95Ms != null ? t.p95Ms : '—'}</td>
                <td>${t.p99Ms != null ? t.p99Ms : '—'}</td>
            </tr>`).join('');
    }

    /**
     * 渲染修复提案列表（HITL 审批入口）。
     */
    renderProposals(data) {
        const box = document.getElementById('proposalList');
        if (!box) return;
        const proposals = (data && Array.isArray(data.proposals)) ? data.proposals : [];
        if (proposals.length === 0) {
            box.innerHTML = '<p class="empty-state">No repair proposals</p>';
            return;
        }
        box.innerHTML = proposals.map(p => this.renderProposalCard(p)).join('');
    }

    /**
     * 单张提案卡：状态徽章 + 工具/类型 + 摘要 + 根因 + 内容折叠 + 动作按钮。
     */
    renderProposalCard(p) {
        const id = this.escapeAttr(p.proposalId);
        const status = String(p.status || 'PENDING');
        const actions = this.proposalActions(p, id);
        const rootCause = p.rootCauseAnalysis
            ? `<div class="proposal-section"><span class="proposal-section-label">Root Cause</span><div class="proposal-section-body">${this.escapeHtml(p.rootCauseAnalysis)}</div></div>`
            : '';
        const impact = p.impactScore != null
            ? `<span class="proposal-impact">impact ${Number(p.impactScore).toFixed(2)}</span>` : '';
        return `
            <div class="proposal-card" data-status="${this.escapeAttr(status)}">
                <div class="proposal-head">
                    <span class="badge ${this.proposalStatusBadge(status)}">${this.escapeHtml(status)}</span>
                    <span class="proposal-tool">${this.escapeHtml(p.toolName || '')}</span>
                    <span class="badge badge-outline">${this.escapeHtml(p.type || '')}</span>
                    ${impact}
                </div>
                <div class="proposal-summary">${this.escapeHtml(p.summary || '(no summary)')}</div>
                ${rootCause}
                ${this.renderProposalDiff(p)}
                ${actions ? `<div class="proposal-actions">${actions}</div>` : ''}
            </div>`;
    }

    /**
     * 提案状态 → 徽章样式映射。
     */
    proposalStatusBadge(status) {
        switch (String(status).toUpperCase()) {
            case 'PENDING': return 'badge-timeout';
            case 'APPROVED': return 'badge-outline';
            case 'APPLIED': return 'badge-success';
            case 'REJECTED': return 'badge-error';
            default: return 'badge-disabled';
        }
    }

    /**
     * 依状态给出可用动作：PENDING→审批/拒绝；APPROVED→应用；其余仅查看。
     */
    proposalActions(p, id) {
        const status = String(p.status || '').toUpperCase();
        const view = `<button class="btn btn-text" onclick="app.viewProposal('${id}')">Details</button>`;
        if (status === 'PENDING') {
            return view
                + `<button class="btn btn-text" onclick="app.proposalAction('${id}','approve')">✓ Approve</button>`
                + `<button class="btn btn-text btn-danger" onclick="app.proposalAction('${id}','reject')">✗ Reject</button>`;
        }
        if (status === 'APPROVED') {
            return view + `<button class="btn btn-text" onclick="app.proposalAction('${id}','apply')">▶ Apply</button>`;
        }
        return view;
    }

    /**
     * 折叠展示提案的原始内容与拟改内容。
     */
    renderProposalDiff(p) {
        if (!p.proposedContent) return '';
        const original = p.originalContent
            ? `<div class="diff-block diff-old"><span class="diff-label">Original</span><pre>${this.escapeHtml(p.originalContent)}</pre></div>`
            : '';
        const proposed = `<div class="diff-block diff-new"><span class="diff-label">Proposed</span><pre>${this.escapeHtml(p.proposedContent)}</pre></div>`;
        return `<details class="proposal-diff"><summary>View content</summary>${original}${proposed}</details>`;
    }

    /**
     * 弹窗查看单个提案的完整详情。
     */
    async viewProposal(id) {
        try {
            const resp = await this.authFetch('/api/reflection/proposals');
            const data = resp.ok ? await resp.json() : null;
            const list = (data && Array.isArray(data.proposals)) ? data.proposals : [];
            const p = list.find(x => String(x.proposalId) === String(id));
            if (!p) { this.showToast('Proposal not found', 'error'); return; }
            this.showModal(`Proposal: ${p.toolName || id}`, `
                <div class="proposal-detail">
                    <div><strong>Status:</strong> ${this.escapeHtml(String(p.status || ''))}</div>
                    <div><strong>Type:</strong> ${this.escapeHtml(String(p.type || ''))}</div>
                    <div><strong>Impact:</strong> ${p.impactScore != null ? Number(p.impactScore).toFixed(2) : '—'}</div>
                    <div><strong>Summary:</strong> ${this.escapeHtml(p.summary || '')}</div>
                    ${p.rootCauseAnalysis ? `<div><strong>Root Cause:</strong><pre class="proposal-pre">${this.escapeHtml(p.rootCauseAnalysis)}</pre></div>` : ''}
                    ${p.originalContent ? `<div><strong>Original:</strong><pre class="proposal-pre">${this.escapeHtml(p.originalContent)}</pre></div>` : ''}
                    ${p.proposedContent ? `<div><strong>Proposed:</strong><pre class="proposal-pre">${this.escapeHtml(p.proposedContent)}</pre></div>` : ''}
                </div>`, null);
            document.getElementById('modalConfirm').style.display = 'none';
        } catch (e) {
            console.error('Failed to load proposal:', e);
            this.showToast('Failed to load proposal', 'error');
        }
    }

    /**
     * 对提案执行 approve/reject/apply 动作，成功后刷新列表。
     */
    async proposalAction(id, action) {
        if (action === 'apply' && !confirm('Apply this approved repair? It will modify tool configuration.')) return;
        if (action === 'reject' && !confirm('Reject this proposal?')) return;
        try {
            const resp = await this.authFetch(`/api/reflection/proposals/${encodeURIComponent(id)}/${action}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json().catch(() => ({}));
            if (resp.ok) {
                this.showToast(data.message || `${action} succeeded`, 'success');
                this.loadReflection();
            } else {
                this.showToast(data.error || `${action} failed (${resp.status})`, 'error');
            }
        } catch (e) {
            this.showToast(`Network error during ${action}`, 'error');
        }
    }
}

// Initialize app
const app = new TinyClawConsole();
