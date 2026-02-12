## 🐦 通过飞书对话与 TinyClaw 交互指南

本文档介绍如何配置飞书机器人，使你可以在飞书中直接与 TinyClaw AI Agent 进行对话交互。

---

### 📋 前置条件

- 已完成 TinyClaw 项目构建（`mvn clean package -DskipTests`）
- 已完成初始化配置（`java -jar target/tinyclaw-0.1.0.jar onboard`）
- 已配置至少一个 LLM 提供商（如 DashScope、OpenRouter、智谱 GLM 等）
- 拥有飞书企业管理员权限或开发者权限

---

### 第一步：创建飞书企业自建应用

1. 访问 [飞书开放平台](https://open.feishu.cn/app)，使用管理员账号登录
2. 点击 **创建企业自建应用**
3. 填写应用名称（如 `TinyClaw`）和描述，完成创建

### 第二步：添加机器人能力

1. 进入刚创建的应用，点击左侧菜单 **应用能力** → **添加应用能力**
2. 选择 **机器人** 能力并添加
3. 在机器人配置页面中：
   - **消息接收模式**：选择 **使用长连接接收消息** 或 **HTTP 推送**
   - 如果选择 HTTP 推送，配置 **请求地址**：`http://<你的服务器IP>:<端口>/api/feishu/webhook`
     - 默认端口为 `18790`（可在配置中修改）
     - 示例：`http://123.45.67.89:18790/api/feishu/webhook`

> **💡 提示**：请求地址必须是公网可访问的 HTTPS 或 HTTP 地址。如果在本地开发，可以使用 [ngrok](https://ngrok.com/) 等内网穿透工具。

### 第三步：配置权限

在应用的 **权限管理** 页面，申请以下权限：

| 权限名称 | 权限标识 | 说明 |
|----------|----------|------|
| 获取与发送单聊、群组消息 | `im:message` | 发送和接收消息 |
| 读取用户信息 | `contact:user.base:readonly` | 获取用户基本信息 |
| 获取群组信息 | `im:chat:readonly` | 获取群聊信息 |

申请完成后，需要管理员在 **飞书管理后台** 审批通过。

### 第四步：配置事件订阅

1. 在应用的 **事件与回调** → **事件配置** 页面
2. 添加事件：**接收消息**（`im.message.receive_v1`）
3. 配置 **Encrypt Key**（加密密钥）和 **Verification Token**（验证令牌），记录下来用于后续配置

### 第五步：获取凭证信息

在应用的 **凭证与基础信息** 页面，记录以下信息：

| 字段 | 说明 |
|------|------|
| **App ID** | 应用的唯一标识 |
| **App Secret** | 应用的密钥，用于获取访问令牌 |
| **Encrypt Key** | 事件订阅的加密密钥（可选） |
| **Verification Token** | 事件订阅的验证令牌（可选） |

### 第六步：发布应用

1. 在应用的 **版本管理与发布** 页面
2. 创建版本并提交审核
3. 管理员在 **飞书管理后台** 审批通过后，应用即可使用
4. 设置应用的 **可用范围**，指定哪些用户或部门可以使用该机器人

### 第七步：获取用户 ID

你需要获取允许与机器人交互的用户 ID，用于配置白名单。飞书支持多种用户 ID 类型：

- **user_id**：飞书内部用户 ID
- **open_id**：应用维度的用户 ID
- **union_id**：跨应用的用户 ID

获取方式：
1. 通过飞书管理后台查看用户详情
2. 通过飞书开放平台 API 获取
3. 也可以先不配置 `allowFrom`（留空数组），此时所有用户都可以与机器人交互

### 第八步：配置 TinyClaw

编辑 `~/.tinyclaw/config.json`，添加飞书通道配置：

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
    "feishu": {
      "enabled": true,
      "appId": "cli_your_app_id",
      "appSecret": "your-app-secret",
      "encryptKey": "your-encrypt-key",
      "verificationToken": "your-verification-token",
      "allowFrom": ["ou_your_open_id"]
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
| `enabled` | 是 | 设为 `true` 启用飞书通道 |
| `appId` | 是 | 飞书应用的 App ID |
| `appSecret` | 是 | 飞书应用的 App Secret，用于获取 tenant_access_token |
| `encryptKey` | 否 | 事件订阅的加密密钥，用于解密事件回调 |
| `verificationToken` | 否 | 事件订阅的验证令牌，用于验证请求来源 |
| `allowFrom` | 否 | 允许交互的用户 ID 白名单（支持 user_id、open_id、union_id），留空数组表示允许所有用户 |

> **🔐 安全提示**：建议配置 `allowFrom` 白名单，限制只有授权用户才能与 Agent 交互，避免未授权访问。

### 第九步：启动网关服务

使用网关模式启动 TinyClaw，它会自动连接所有已启用的通道：

```bash
java -jar target/tinyclaw-0.1.0.jar gateway
```

启动成功后，你会看到类似以下日志：

```
[INFO] Initializing channel manager
[INFO] Feishu channel enabled successfully
[INFO] 飞书通道已启动（HTTP API 模式）
[INFO] 请配合 Webhook 服务使用以接收消息
[INFO] All channels started
```

### 第十步：开始对话

1. 在飞书中搜索你创建的机器人名称
2. 发起单聊或在群中 @机器人
3. 发送消息即可开始与 TinyClaw AI Agent 交互

#### 单聊模式

直接在飞书中找到机器人，发送消息即可：

```
你：你好，介绍一下你自己
TinyClaw：你好！我是 TinyClaw，你的个人 AI 助手...
```

#### 群聊模式

将机器人添加到群聊后，@机器人发送消息：

```
你：@TinyClaw 帮我总结一下今天的会议纪要
TinyClaw：好的，正在为你整理会议纪要...
```

---

### 🔧 进阶配置

#### 使用不同的 LLM 模型

修改 `config.json` 中的 `model` 字段即可切换模型：

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
- 确认消息发送者的 ID 在 `allowFrom` 白名单中（如果配置了白名单）
- 确认应用权限已审批通过

#### Q: 提示"获取飞书访问令牌失败"？

- 确认 `appId` 和 `appSecret` 填写正确
- 确认应用已发布并审批通过
- 检查网络是否能访问 `https://open.feishu.cn`

#### Q: 飞书提示"请求地址不可达"？

- 确认 TinyClaw 网关服务已启动
- 确认服务器防火墙已开放对应端口（默认 `18790`）
- 确认请求地址是公网可访问的

#### Q: 如何在本地开发调试？

使用内网穿透工具将本地端口暴露到公网：

```bash
# 使用 ngrok
ngrok http 18790
```

将 ngrok 生成的公网地址填入飞书应用的事件请求地址。

#### Q: 访问令牌过期了怎么办？

TinyClaw 会自动管理 `tenant_access_token` 的刷新。令牌默认有效期为 2 小时，系统会在过期前 5 分钟自动刷新，无需手动干预。

#### Q: 如何同时使用飞书和其他通道？

TinyClaw 支持同时启用多个通道。只需在 `config.json` 中同时配置多个通道即可，网关模式会自动连接所有已启用的通道。例如同时启用飞书和钉钉：

```json
{
  "channels": {
    "feishu": {
      "enabled": true,
      "appId": "...",
      "appSecret": "..."
    },
    "dingtalk": {
      "enabled": true,
      "clientId": "...",
      "clientSecret": "..."
    }
  }
}
```

---

### 📐 架构说明

飞书通道的消息流转过程如下：

```
用户在飞书发送消息
    ↓
飞书服务器推送事件到 TinyClaw 网关（HTTP Webhook）
    ↓
FeishuChannel.handleIncomingMessage() 解析消息事件
    ↓
提取发送者 ID（user_id / open_id / union_id）
    ↓
权限检查（allowFrom 白名单）
    ↓
发布到 MessageBus（消息总线）
    ↓
AgentLoop 处理消息，调用 LLM 生成回复
    ↓
使用 tenant_access_token 调用飞书 API 发送回复
    ↓
用户在飞书收到回复
```

**关键特性**：
- **自动令牌管理**：使用 App ID 和 App Secret 自动获取和刷新 `tenant_access_token`，过期前 5 分钟自动续期
- **多种用户 ID 支持**：白名单支持 `user_id`、`open_id`、`union_id` 三种用户标识
- **文本消息解析**：自动解析飞书的结构化消息格式，提取纯文本内容
- **群聊支持**：通过 `chat_id` 区分不同会话，支持单聊和群聊场景
