## 🔔 通过钉钉对话与 TinyClaw 交互指南

本文档介绍如何配置钉钉机器人，使你可以在钉钉中直接与 TinyClaw AI Agent 进行对话交互。

---

### 📋 前置条件

- 已完成 TinyClaw 项目构建（`mvn clean package -DskipTests`）
- 已完成初始化配置（`java -jar target/tinyclaw-0.1.0.jar onboard`）
- 已配置至少一个 LLM 提供商（如 DashScope、OpenRouter、智谱 GLM 等）
- 拥有钉钉企业管理员权限或开发者权限

---

### 第一步：创建钉钉企业内部应用

1. 访问 [钉钉开放平台](https://open-dev.dingtalk.com/)，使用管理员账号登录
2. 点击 **应用开发** → **企业内部开发** → **创建应用**
3. 填写应用名称（如 `TinyClaw`）和描述，完成创建

### 第二步：添加机器人能力

1. 进入刚创建的应用，点击左侧菜单 **应用能力** → **机器人**
2. 开启机器人功能
3. 配置机器人信息：
   - **机器人名称**：`TinyClaw`（或你喜欢的名称）
   - **消息接收模式**：选择 **HTTP 模式**
   - **消息接收地址**：填写你的服务器地址，格式为 `http://<你的服务器IP>:<端口>/api/dingtalk/webhook`
     - 默认端口为 `18790`（可在配置中修改）
     - 示例：`http://123.45.67.89:18790/api/dingtalk/webhook`

> **💡 提示**：消息接收地址必须是公网可访问的地址。如果在本地开发，可以使用 [ngrok](https://ngrok.com/) 等内网穿透工具。

### 第三步：获取凭证信息

在应用的 **凭证与基础信息** 页面，记录以下信息：

| 字段 | 说明 |
|------|------|
| **Client ID**（AppKey） | 应用的唯一标识 |
| **Client Secret**（AppSecret） | 应用的密钥，用于签名验证 |

### 第四步：获取你的钉钉用户 ID

你需要获取允许与机器人交互的用户的 **staffId**，用于配置白名单：

1. 在钉钉管理后台 → **通讯录** 中查看用户详情
2. 或通过钉钉开放平台 API 获取用户 ID
3. 也可以先不配置 `allowFrom`（留空数组），此时所有用户都可以与机器人交互

### 第五步：配置 TinyClaw

编辑 `~/.tinyclaw/config.json`，添加钉钉通道配置：

```json
{
  "agents": {
    "defaults": {
      "model": "qwen-plus"
    }
  },
  "providers": {
    "dashscope": {
      "apiKey": "sk-your-dashscope-api-key",
      "apiBase": "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
  },
  "channels": {
    "dingtalk": {
      "enabled": true,
      "clientId": "your-dingtalk-client-id",
      "clientSecret": "your-dingtalk-client-secret",
      "webhook": "",
      "allowFrom": ["your-staff-id"]
    }
  },
  "gateway": {
    "host": "0.0.0.0",
    "port": 18790
  }
}
```

#### 配置字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `enabled` | 是 | 设为 `true` 启用钉钉通道 |
| `clientId` | 是 | 钉钉应用的 Client ID（AppKey） |
| `clientSecret` | 是 | 钉钉应用的 Client Secret（AppSecret） |
| `webhook` | 否 | 自定义 Webhook 地址，通常留空即可（系统会自动使用 session_webhook） |
| `allowFrom` | 否 | 允许交互的用户 staffId 白名单，留空数组表示允许所有用户 |

> **🔐 安全提示**：建议配置 `allowFrom` 白名单，限制只有授权用户才能与 Agent 交互，避免未授权访问。

你也可以通过环境变量配置敏感信息，避免将密钥写入配置文件：

```bash
export TINYCLAW_PROVIDERS_DASHSCOPE_API_KEY="sk-your-dashscope-api-key"
```

### 第六步：启动网关服务

使用网关模式启动 TinyClaw，它会自动连接所有已启用的通道：

```bash
java -jar target/tinyclaw-0.1.0.jar gateway
```

启动成功后，你会看到类似以下日志：

```
[INFO] Initializing channel manager
[INFO] DingTalk channel enabled successfully
[INFO] 钉钉通道已启动（Webhook 模式）
[INFO] 请确保已配置钉钉机器人的消息接收地址
[INFO] All channels started
```

### 第七步：开始对话

1. 在钉钉中搜索你创建的机器人名称
2. 发起单聊或在群中 @机器人
3. 发送消息即可开始与 TinyClaw AI Agent 交互

#### 单聊模式

直接在钉钉中找到机器人，发送消息即可：

```
你：你好，介绍一下你自己
TinyClaw：你好！我是 TinyClaw，你的个人 AI 助手...
```

#### 群聊模式

在群中 @机器人发送消息：

```
你：@TinyClaw 帮我查一下今天的天气
TinyClaw：正在为你查询天气信息...
```

---

### 🔧 进阶配置

#### 使用不同的 LLM 模型

你可以根据需要切换不同的模型，只需修改 `config.json` 中的 `model` 字段：

```json
{
  "agents": {
    "defaults": {
      "model": "qwen-turbo"
    }
  }
}
```

**DashScope 支持的常用模型**：

| 模型名 | 说明 |
|--------|------|
| `qwen-turbo` | 速度快，适合日常对话 |
| `qwen-plus` | 能力均衡，推荐使用 |
| `qwen-max` | 最强能力，适合复杂任务 |
| `qwen-long` | 支持超长上下文 |

#### 后台运行

生产环境建议使用 `nohup` 或 `tmux` 在后台运行：

```bash
# 使用 nohup
nohup java -jar target/tinyclaw-0.1.0.jar gateway > tinyclaw.log 2>&1 &

# 使用 tmux
tmux new -s tinyclaw
java -jar target/tinyclaw-0.1.0.jar gateway
# 按 Ctrl+B, D 分离会话
```

#### 查看运行状态

```bash
java -jar target/tinyclaw-0.1.0.jar status
```

---

### ❓ 常见问题

#### Q: 机器人收到消息但没有回复？

- 检查 LLM 提供商的 API Key 是否正确配置
- 查看日志中是否有错误信息
- 确认消息发送者的 staffId 在 `allowFrom` 白名单中（如果配置了白名单）

#### Q: 钉钉提示"消息接收地址不可达"？

- 确认 TinyClaw 网关服务已启动
- 确认服务器防火墙已开放对应端口（默认 `18790`）
- 确认消息接收地址是公网可访问的

#### Q: 如何在本地开发调试？

使用内网穿透工具将本地端口暴露到公网：

```bash
# 使用 ngrok
ngrok http 18790
```

将 ngrok 生成的公网地址填入钉钉机器人的消息接收地址。

#### Q: 回复消息显示"未找到 session_webhook"？

这通常发生在服务重启后。钉钉的 `session_webhook` 是临时的，服务重启后会丢失。用户重新发送一条消息即可恢复正常。

#### Q: 如何同时使用钉钉和其他通道？

TinyClaw 支持同时启用多个通道。只需在 `config.json` 中同时配置多个通道即可，网关模式会自动连接所有已启用的通道。

---

### 📐 架构说明

钉钉通道的消息流转过程如下：

```
用户在钉钉发送消息
    ↓
钉钉服务器推送到 TinyClaw 网关（HTTP Webhook）
    ↓
DingTalkChannel.handleIncomingMessage() 解析消息
    ↓
权限检查（allowFrom 白名单）
    ↓
发布到 MessageBus（消息总线）
    ↓
AgentLoop 处理消息，调用 LLM 生成回复
    ↓
通过 session_webhook 发送 Markdown 格式回复到钉钉
    ↓
用户在钉钉收到回复
```

**关键特性**：
- **Markdown 支持**：Agent 的回复以 Markdown 格式发送，在钉钉中可以正确渲染
- **session_webhook 机制**：每次收到消息时自动缓存回复地址，确保消息能正确回复
- **群聊支持**：自动识别单聊和群聊场景，群聊中通过 `conversationId` 区分会话
