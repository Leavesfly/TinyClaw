// TinyClaw Web Console - App

class TinyClawConsole {
    constructor() {
        this.currentPage = 'chat';
        this.chatSessionId = 'web:default';
        this.allSessions = [];
        this.currentSessionPage = 1;
        this.init();
    }

    init() {
        // 配置 Markdown 渲染：启用换行符转 <br>
        if (typeof marked !== 'undefined') {
            marked.setOptions({ breaks: true });
        }
        
        this.bindNavigation();
        this.bindChat();
        this.bindModal();
        this.loadInitialPage();
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
            models: 'Models',
            environments: 'Environments'
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
            case 'models': this.loadProviders(); this.loadCurrentModel(); break;
            case 'environments': this.loadAgentConfig(); break;
        }
    }

    // ==================== Chat ====================

    bindChat() {
        const input = document.getElementById('chatInput');
        const sendBtn = document.getElementById('sendBtn');
        const newChatBtn = document.getElementById('newChatBtn');

        sendBtn.addEventListener('click', () => this.sendMessage());
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });

        input.addEventListener('input', () => {
            input.style.height = 'auto';
            input.style.height = Math.min(input.scrollHeight, 120) + 'px';
        });

        newChatBtn.addEventListener('click', () => {
            this.chatSessionId = 'web:' + Date.now();
            document.getElementById('chatMessages').innerHTML = this.getWelcomeHtml();
            this.bindQuickPrompts();
        });

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
     * 加载当前 session 的聊天历史
     */
    async loadChatHistory() {
        try {
            const response = await fetch(`/api/sessions/${encodeURIComponent(this.chatSessionId)}`);
            if (!response.ok) return;
            
            const messages = await response.json();
            if (!messages || messages.length === 0) return;
            
            const messagesDiv = document.getElementById('chatMessages');
            // 清除欢迎消息
            messagesDiv.innerHTML = '';
            
            // 显示历史消息
            for (const msg of messages) {
                if (msg.role === 'user' || msg.role === 'assistant') {
                    this.addMessage(msg.content || '', msg.role);
                }
            }
        } catch (error) {
            console.error('Failed to load chat history:', error);
        }
    }

    /**
     * 加载左侧历史聊天会话列表
     */
    async loadChatSessions() {
        try {
            const response = await fetch('/api/sessions');
            const sessions = await response.json();
            
            // 只显示 web: 开头的会话，按时间戳降序排列（最新的在最上面）
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
            
            historyDiv.innerHTML = webSessions.map(s => {
                const isActive = s.key === this.chatSessionId;
                const title = this.extractChatTitle(s.key);
                return `
                    <div class="chat-history-item ${isActive ? 'active' : ''}" data-session="${this.escapeHtml(s.key)}">
                        <span class="history-title">${this.escapeHtml(title)}</span>
                        <button class="history-delete" onclick="event.stopPropagation(); app.deleteChatSession('${this.escapeHtml(s.key)}')" title="Delete">×</button>
                    </div>
                `;
            }).join('');
            
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
     * 从会话 key 提取标题（用第一条用户消息或时间戳）
     */
    extractChatTitle(key) {
        // web:1234567890 -> 显示时间
        if (key.startsWith('web:')) {
            const timestamp = key.substring(4);
            if (/^\d+$/.test(timestamp)) {
                const date = new Date(parseInt(timestamp));
                return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
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
        this.loadChatHistory();
        this.loadChatSessions(); // 刷新列表高亮
    }

    /**
     * 删除聊天会话
     */
    async deleteChatSession(key) {
        if (!confirm('Delete this chat?')) return;
        try {
            await fetch(`/api/sessions/${encodeURIComponent(key)}`, { method: 'DELETE' });
            // 如果删除的是当前会话，切换到新会话
            if (key === this.chatSessionId) {
                this.chatSessionId = 'web:default';
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
        const message = input.value.trim();
        if (!message) return;

        input.value = '';
        input.style.height = 'auto';

        const messagesDiv = document.getElementById('chatMessages');
        
        // Remove welcome message
        const welcome = messagesDiv.querySelector('.chat-welcome');
        if (welcome) welcome.remove();

        // Add user message
        this.addMessage(message, 'user');

        // Add assistant message placeholder for streaming
        const assistantDiv = document.createElement('div');
        assistantDiv.className = 'message assistant';
        assistantDiv.innerHTML = '<div class="message-content"><span class="streaming-cursor"></span></div>';
        messagesDiv.appendChild(assistantDiv);
        messagesDiv.scrollTop = messagesDiv.scrollHeight;
        
        const contentDiv = assistantDiv.querySelector('.message-content');
        let fullResponse = '';
        // 跟踪同一个 SSE 消息内是否已有 data: 行
        // SSE 协议：同一消息内多个 data: 行之间用 \n 分隔，消息之间用 \n\n（空行）分隔
        let dataLineCountInCurrentMessage = 0;

        try {
            // 使用流式 API
            const response = await fetch('/api/chat/stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message, sessionId: this.chatSessionId })
            });

            const reader = response.body.getReader();
            const decoder = new TextDecoder();

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                const chunk = decoder.decode(value, { stream: true });
                const lines = chunk.split('\n');

                for (const line of lines) {
                    if (line.startsWith('data: ')) {
                        const data = line.slice(6);
                        if (data === '[DONE]') {
                            break;
                        } else if (data.startsWith('[ERROR]')) {
                            fullResponse += data.slice(8);
                        } else {
                            // 同一 SSE 消息内的第 2+ 个 data: 行，需要还原换行符
                            if (dataLineCountInCurrentMessage > 0) {
                                fullResponse += '\n';
                            }
                            fullResponse += data;
                        }
                        dataLineCountInCurrentMessage++;
                        // 流式过程中使用 escapeHtml 并将换行转为 <br> 显示
                        contentDiv.innerHTML = this.escapeHtml(fullResponse).replace(/\n/g, '<br>') + '<span class="streaming-cursor"></span>';
                        messagesDiv.scrollTop = messagesDiv.scrollHeight;
                    } else if (line === '') {
                        // 空行表示当前 SSE 消息结束，重置计数器
                        dataLineCountInCurrentMessage = 0;
                    }
                }
            }

            // 移除光标，使用 Markdown 渲染最终内容
            if (typeof marked !== 'undefined') {
                contentDiv.classList.add('markdown-body');
                contentDiv.innerHTML = marked.parse(fullResponse);
            } else {
                contentDiv.innerHTML = this.escapeHtml(fullResponse);
            }
            
            // 刷新左侧会话列表
            this.loadChatSessions();
        } catch (error) {
            contentDiv.innerHTML = this.escapeHtml('Error: ' + error.message);
        }
    }

    addMessage(content, role) {
        const messagesDiv = document.getElementById('chatMessages');
        const div = document.createElement('div');
        div.className = `message ${role}`;
        
        // assistant 消息使用 Markdown 渲染，user 消息纯文本
        if (role === 'assistant' && typeof marked !== 'undefined') {
            div.innerHTML = `<div class="message-content markdown-body">${marked.parse(content)}</div>`;
        } else {
            div.innerHTML = `<div class="message-content">${this.escapeHtml(content)}</div>`;
        }
        
        messagesDiv.appendChild(div);
        messagesDiv.scrollTop = messagesDiv.scrollHeight;
    }

    // ==================== Channels ====================

    async loadChannels() {
        try {
            const response = await fetch('/api/channels');
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
            const response = await fetch(`/api/channels/${name}`);
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
                
                await fetch(`/api/channels/${name}`, {
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
            const response = await fetch('/api/sessions');
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
        
        // 分页
        document.getElementById('prevPage').addEventListener('click', () => {
            if (this.currentSessionPage > 1) {
                this.currentSessionPage--;
                this.renderSessionsTable();
            }
        });
        
        document.getElementById('nextPage').addEventListener('click', () => {
            const pageSize = 10;
            const totalPages = Math.ceil(this.allSessions.length / pageSize);
            if (this.currentSessionPage < totalPages) {
                this.currentSessionPage++;
                this.renderSessionsTable();
            }
        });
    }
    
    async viewSessionDetail(key) {
        try {
            const response = await fetch(`/api/sessions/${encodeURIComponent(key)}`);
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
            await fetch(`/api/sessions/${encodeURIComponent(key)}`, { method: 'DELETE' });
            this.loadSessions();
        } catch (error) {
            console.error('Failed to delete session:', error);
        }
    }

    // ==================== Cron Jobs ====================

    async loadCronJobs() {
        try {
            const response = await fetch('/api/cron');
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

        document.getElementById('addCronBtn').onclick = () => this.showAddCronModal();
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
                <input class="form-control" id="cronExpr" placeholder="0 * * * *">
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
            await fetch('/api/cron', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            this.loadCronJobs();
        });

        document.getElementById('cronType').onchange = (e) => {
            document.getElementById('cronEveryGroup').style.display = e.target.value === 'every' ? 'block' : 'none';
            document.getElementById('cronExprGroup').style.display = e.target.value === 'cron' ? 'block' : 'none';
        };
    }

    async toggleCronJob(id, enabled) {
        await fetch(`/api/cron/${id}/enable`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled })
        });
        this.loadCronJobs();
    }

    async deleteCronJob(id) {
        if (!confirm('Delete this job?')) return;
        await fetch(`/api/cron/${id}`, { method: 'DELETE' });
        this.loadCronJobs();
    }

    // ==================== Workspace ====================

    async loadWorkspaceFiles() {
        try {
            // 获取 workspace 路径
            const configResponse = await fetch('/api/config/agent');
            const config = await configResponse.json();
            // workspace 路径可能在配置中，这里暂时显示默认路径
            
            const response = await fetch('/api/workspace/files');
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
            const response = await fetch(`/api/workspace/files/${encodeURIComponent(name)}`);
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
            await fetch(`/api/workspace/files/${encodeURIComponent(this.currentEditingFile)}`, {
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
                await fetch(`/api/workspace/files/${encodeURIComponent(name)}`, {
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
            const response = await fetch('/api/skills');
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
                    </div>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load skills:', error);
        }
    }

    async viewSkill(name) {
        try {
            const response = await fetch(`/api/skills/${encodeURIComponent(name)}`);
            const skill = await response.json();
            
            this.showModal(`Skill: ${name}`, `
                <pre style="white-space: pre-wrap; font-size: 13px; background: var(--bg); padding: 16px; border-radius: 8px; max-height: 400px; overflow: auto;">${this.escapeHtml(skill.content)}</pre>
            `, null);
            document.getElementById('modalConfirm').style.display = 'none';
        } catch (error) {
            console.error('Failed to load skill:', error);
        }
    }

    // ==================== Models/Providers ====================

    async loadProviders() {
        try {
            // 加载 providers
            const providersResponse = await fetch('/api/providers');
            const providers = await providersResponse.json();
            this.providers = providers;
            
            // 加载 models
            const modelsResponse = await fetch('/api/models');
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
            
            await fetch(`/api/providers/${name}`, {
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
            const response = await fetch('/api/config/model');
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
            
            await fetch('/api/config/model', {
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
            const response = await fetch('/api/config/agent');
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
            
            await fetch('/api/config/agent', {
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
}

// Initialize app
const app = new TinyClawConsole();
