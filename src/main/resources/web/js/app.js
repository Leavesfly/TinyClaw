// TinyClaw Web Console - App

class TinyClawConsole {
    constructor() {
        this.currentPage = 'chat';
        this.chatSessionId = 'web:default';
        this.init();
    }

    init() {
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
            document.getElementById('chatMessages').innerHTML = `
                <div class="chat-welcome">
                    <div class="welcome-icon">🦞</div>
                    <h2>Hello, how can I help you today?</h2>
                    <p>I am a helpful assistant that can help you with your questions.</p>
                </div>
            `;
        });
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

        // Add loading indicator
        const loadingDiv = document.createElement('div');
        loadingDiv.className = 'message assistant';
        loadingDiv.innerHTML = '<div class="message-content"><div class="loading"></div></div>';
        messagesDiv.appendChild(loadingDiv);
        messagesDiv.scrollTop = messagesDiv.scrollHeight;

        try {
            const response = await fetch('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message, sessionId: this.chatSessionId })
            });
            const data = await response.json();
            loadingDiv.remove();
            this.addMessage(data.response || data.error, 'assistant');
        } catch (error) {
            loadingDiv.remove();
            this.addMessage('Error: ' + error.message, 'assistant');
        }
    }

    addMessage(content, role) {
        const messagesDiv = document.getElementById('chatMessages');
        const div = document.createElement('div');
        div.className = `message ${role}`;
        div.innerHTML = `<div class="message-content">${this.escapeHtml(content)}</div>`;
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
            
            const list = document.getElementById('sessionsList');
            if (sessions.length === 0) {
                list.innerHTML = '<p class="empty-state">No sessions yet</p>';
                return;
            }
            
            list.innerHTML = sessions.map(s => `
                <div class="session-item" data-key="${s.key}" onclick="app.viewSession('${s.key}')">
                    <div class="session-key">${s.key}</div>
                    <div class="session-meta">${s.messageCount} messages</div>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load sessions:', error);
        }
    }

    async viewSession(key) {
        document.querySelectorAll('.session-item').forEach(item => {
            item.classList.toggle('active', item.dataset.key === key);
        });

        try {
            const response = await fetch(`/api/sessions/${encodeURIComponent(key)}`);
            const messages = await response.json();
            
            const detail = document.getElementById('sessionDetail');
            if (messages.length === 0) {
                detail.innerHTML = '<p class="empty-state">No messages in this session</p>';
                return;
            }
            
            detail.innerHTML = `
                <div style="margin-bottom: 16px;">
                    <button class="btn btn-secondary btn-sm" onclick="app.deleteSession('${key}')">Delete Session</button>
                </div>
                ${messages.map(m => `
                    <div class="message ${m.role}">
                        <div class="message-content">${this.escapeHtml(m.content)}</div>
                    </div>
                `).join('')}
            `;
        } catch (error) {
            console.error('Failed to load session:', error);
        }
    }

    async deleteSession(key) {
        if (!confirm('Delete this session?')) return;
        try {
            await fetch(`/api/sessions/${encodeURIComponent(key)}`, { method: 'DELETE' });
            this.loadSessions();
            document.getElementById('sessionDetail').innerHTML = '<p class="empty-state">Select a session to view history</p>';
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
            const response = await fetch('/api/workspace/files');
            const files = await response.json();
            
            const list = document.getElementById('workspaceFiles');
            list.innerHTML = files.map(f => `
                <div class="file-item" data-file="${f.name}" onclick="app.loadFile('${f.name}')">
                    <span>📄</span>
                    <span>${f.name}</span>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load workspace files:', error);
        }

        document.getElementById('saveFileBtn').onclick = () => this.saveCurrentFile();
    }

    async loadFile(name) {
        document.querySelectorAll('.file-item').forEach(item => {
            item.classList.toggle('active', item.dataset.file === name);
        });

        try {
            const response = await fetch(`/api/workspace/files/${encodeURIComponent(name)}`);
            const data = await response.json();
            
            document.getElementById('editorFileName').textContent = name;
            document.getElementById('editorContent').value = data.content;
            document.getElementById('editorContent').disabled = false;
            document.getElementById('saveFileBtn').disabled = false;
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
            alert('File saved!');
        } catch (error) {
            alert('Failed to save: ' + error.message);
        }
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
            const response = await fetch('/api/providers');
            const providers = await response.json();
            
            const grid = document.getElementById('providersGrid');
            grid.innerHTML = providers.map(p => `
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">${this.capitalize(p.name)}</span>
                        <span class="badge ${p.authorized ? 'badge-success' : 'badge-disabled'}">${p.authorized ? 'Authorized' : 'Unauthorized'}</span>
                    </div>
                    <div class="card-body">
                        <p>Base URL: ${p.apiBase || 'Default'}</p>
                        <p>API Key: ${p.apiKey || 'Not set'}</p>
                    </div>
                    <div class="card-footer">
                        <button class="btn btn-text" onclick="app.editProvider('${p.name}')">⚙️ Settings</button>
                    </div>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load providers:', error);
        }
    }

    editProvider(name) {
        this.showModal(`Edit ${this.capitalize(name)}`, `
            <div class="form-group">
                <label>API Key</label>
                <input class="form-control" id="modalApiKey" type="password" placeholder="Enter API key">
            </div>
            <div class="form-group">
                <label>API Base URL (optional)</label>
                <input class="form-control" id="modalApiBase" placeholder="Leave empty for default">
            </div>
        `, async () => {
            const data = {};
            const apiKey = document.getElementById('modalApiKey').value;
            const apiBase = document.getElementById('modalApiBase').value;
            if (apiKey) data.apiKey = apiKey;
            if (apiBase) data.apiBase = apiBase;
            
            await fetch(`/api/providers/${name}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            this.loadProviders();
        });
    }

    async loadCurrentModel() {
        try {
            const response = await fetch('/api/config/model');
            const data = await response.json();
            document.getElementById('modelInput').value = data.model || '';
            document.getElementById('activeModelBadge').textContent = `Active: ${data.model || '-'}`;
        } catch (error) {
            console.error('Failed to load model:', error);
        }

        document.getElementById('saveModelBtn').onclick = async () => {
            const model = document.getElementById('modelInput').value;
            await fetch('/api/config/model', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ model })
            });
            document.getElementById('activeModelBadge').textContent = `Active: ${model}`;
            alert('Model saved!');
        };
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
