# 07 · 消息总线与通道

> TinyClaw 如何在通道与 Agent 之间路由消息，以及 7 种 IM 平台的接入方式。

---

## 7.1 总体架构

```text
         (用户)
           │
           ▼
  ┌────────────────┐
  │   IM 平台 SDK   │  Telegram / Discord / Feishu / DingTalk / WhatsApp / QQ / MaixCam
  └────┬───────────┘
       │ webhook / long-poll / websocket
       ▼
  ┌──────────────┐          ┌────────────────────┐
  │   Channel    │ publish ►│   MessageBus       │ consume ► AgentRuntime.run()
  │ (BaseChannel)│          │ inbound queue      │
  └──────────────┘          │ outboundByChannel  │
                            └────────┬───────────┘
                                     │
                                     ▼
                             ChannelManager
                              dispatcher thread
                                     │
                                     ▼
                              Channel.send()
                                     │
                                     ▼
                                 (用户)
```

消息总线解决了两个关键问题：

1. **通道 ↔ Agent 的完全解耦**：Channel 只关心「把平台消息转成 `InboundMessage`」和「把 `OutboundMessage` 发回平台」，不 import 任何 Agent 类型
2. **多通道并发**：`outboundByChannel` 按通道分队列，防止某个慢通道阻塞其他通道

---

## 7.2 MessageBus — 消息总线

### 7.2.1 关键字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `inbound` | `LinkedBlockingQueue<InboundMessage>` | 入站队列，默认容量 100 |
| `outboundByChannel` | `ConcurrentHashMap<String, LinkedBlockingQueue<OutboundMessage>>` | 按通道划分的出站队列，每个容量 100 |
| `closed` | `AtomicBoolean` | 关闭标志 |
| `droppedInboundCount` / `droppedOutboundCount` | `AtomicLong` | 统计被丢弃的消息数 |

容量可通过 JVM 系统属性覆盖：

- `-Dtinyclaw.bus.inbound.queue.size=200`
- `-Dtinyclaw.bus.outbound.queue.size=200`

### 7.2.2 核心 API

| 方法 | 用途 |
|------|------|
| `publishInbound(msg)` | Channel 发布用户消息；队列满会丢弃并 `error` 日志 |
| `consumeInbound()` | AgentRuntime 阻塞消费（1 秒 poll + 循环） |
| `consumeInbound(timeout, unit)` | 超时消费 |
| `publishOutbound(msg)` | Agent 发布回复，按 `channel` 字段路由到对应队列 |
| `subscribeOutbound(channel)` | 通道消费自己的出站队列 |
| `close()` | 立即关闭，丢弃剩余消息 |
| `drainAndClose(timeout, unit)` | 等待队列排空后关闭（网关优雅停机用） |
| `clear()` | 清空所有队列 |

### 7.2.3 关闭后行为

- 已关闭时继续调用 `publishInbound/publishOutbound` 会抛 `BusClosedException`
- 消费者从阻塞中唤醒时若队列空，同样抛 `BusClosedException`，使主循环能优雅退出

---

## 7.3 消息模型

### 7.3.1 InboundMessage

| 字段 | 说明 |
|------|------|
| `channel` | 来源通道名（`telegram` / `feishu` / `cli` / `system` 等） |
| `senderId` | 发送者标识（用于白名单校验） |
| `chatId` | 会话 ID（群/私聊标识） |
| `content` | 文本内容 |
| `media` | 媒体 URL 列表（图片、音频） |
| `command` | 指令名（非空表示这是指令消息，如 `new_session`） |
| `metadata` | 额外元信息（平台原始字段、消息 ID 等） |
| `sessionKey` | 动态计算为 `channel:chatId`，可被 `setSessionKey` 覆盖 |
| `receivedAt` | 到达总线的 `Instant`，用于链路追踪 |

辅助方法：`isCommand()`、`getSessionKey()`、`toString()` 自动脱敏过长内容。

### 7.3.2 OutboundMessage

| 字段 | 说明 |
|------|------|
| `channel` | 目标通道 |
| `chatId` | 目标会话 |
| `content` | 文本 |
| `media` | 媒体附件 |
| `metadata` | 额外信息（引用消息 ID、@ 目标等） |

---

## 7.4 Channel 接口家族

### 7.4.1 Channel

```java
interface Channel {
    String name();
    void start();
    void stop();
    void send(OutboundMessage msg);
    boolean isAllowed(String senderId);
    boolean supportsStreaming();  // 默认 false
}
```

### 7.4.2 BaseChannel

所有通道实现继承自 `BaseChannel`，统一处理：

- `allowFrom` 白名单校验（`isAllowed`）
- 日志脱敏
- `publishInbound` 前的公共校验
- 异常兜底与重试

### 7.4.3 ChannelManager

- 根据 `ChannelsConfig` 实例化所有 `enabled=true` 的通道
- 统一 `startAll` / `stopAll`
- 为每个通道启动独立 **dispatcher 线程**：从 `bus.subscribeOutbound(channel)` 取消息 → `channel.send(...)`
- 支持 `dispatchTo(name, msg)` 主动派发

### 7.4.4 WebhookServer

内置轻量 HTTP 服务器（基于 JDK `HttpServer`），为 `webhook` 模式的通道（飞书、钉钉）提供回调入口。

---

## 7.5 7 种内置通道

### 7.5.1 Telegram

- 实现：`TelegramChannel`
- 协议：HTTP long-polling（`getUpdates`）
- 凭证：`token`
- 支持流式：❌（使用「编辑消息」模拟进度）
- 特性：自动转写语音（调用 `AliyunTranscriber`）、图片附件自动下载

### 7.5.2 Discord

- 实现：`DiscordChannel`
- 协议：Gateway WebSocket + REST
- 凭证：Bot `token`
- 支持流式：❌
- 特性：Slash Commands 支持（把 `/new` 等 InboundMessage.command 映射到 Discord 斜杠命令）

### 7.5.3 飞书 Feishu

- 实现：`FeishuChannel`
- 协议：
  - **websocket 模式**：长连接（无需公网 IP）← 推荐
  - **webhook 模式**：经 `WebhookServer` 回调
- 凭证：`appId` + `appSecret`（+ `encryptKey` / `verificationToken` 若加密）
- 支持流式：✅（边生成边「编辑消息」）
- 详见 `docs/feishu-guide.md`

### 7.5.4 钉钉 DingTalk

- 实现：`DingTalkChannel`
- 协议：
  - **stream 模式**：Stream API 长连接 ← 推荐
  - **webhook 模式**：企业内机器人回调
- 凭证：`clientId` + `clientSecret`
- 支持流式：✅
- 详见 `docs/dingtalk-guide.md`

### 7.5.5 WhatsApp

- 实现：`WhatsAppChannel`
- 协议：走外部 Bridge（如 `whatsapp-bridge`）的 REST
- 凭证：`bridgeUrl`
- 支持流式：❌

### 7.5.6 QQ

- 实现：`QQChannel`
- 协议：QQ 开放平台 OAuth + WebSocket
- 凭证：`appId` + `appSecret`
- 支持流式：❌

### 7.5.7 MaixCam

- 实现：`MaixCamChannel`
- 协议：自研轻量 HTTP 协议，适配 MaixCam 边缘摄像头
- 凭证：`host` + `port`
- 支持流式：✅

---

## 7.6 CLI 作为特殊通道

CLI / Web 不通过 `MessageBus.inbound`，而是直接调 `AgentRuntime.processDirect*`，但出站时仍会走 `publishOutbound`（用 `channel=cli` 或 `channel=web`），以便 Cron / SubAgent 等触发的异步回复能找到原通道。

- **CLI** 的特殊之处：其 chatId 默认为 `default`，sessionKey 默认为 `cli:default`
- **Web** 的特殊之处：WebSocket / SSE 回调封装成 `StreamCallback`，直接对接 `processDirectStream`

---

## 7.7 白名单与安全

每个通道配置都有 `allowFrom: List<String>`：

- 非空 → 只有匹配列表中的 senderId 才会 `publishInbound`
- 空 → 默认拒绝（空列表 = 拒绝所有，避免误开放）

`isAllowed(senderId)` 的实现位于 `BaseChannel`，具体匹配规则各通道略有差异（如飞书按 `open_id` / 钉钉按 `unionId`）。

---

## 7.8 语音消息处理

各通道在收到音频消息时：

1. 下载音频到临时文件
2. 调 `Transcriber.transcribe(filePath)` 转文字
3. 把文字作为 `content`，原音频 URL 放进 `media`
4. 发布到 MessageBus

默认实现：`AliyunTranscriber`（DashScope Paraformer-v2），详见 [18 · 语音转写](18-voice.md)。

---

## 7.9 关键数据流（以飞书为例）

```text
用户 @机器人 "帮我定个闹钟"
          │
          ▼
飞书 Open Platform 回调 / WebSocket 推送事件
          │
          ▼
FeishuChannel.onEvent(...)
  ├─ 校验签名 / 解密（若 encryptKey 不空）
  ├─ 提取 open_id / content
  ├─ isAllowed(open_id)? 否则 drop
  └─ bus.publishInbound(InboundMessage)
          │
          ▼
AgentRuntime.run() consumeInbound()
          │
          ▼
MessageRouter.routeUser(msg)
  └─ ... LLM + 工具循环 ...
          │
          ▼
bus.publishOutbound(OutboundMessage{channel="feishu", chatId=open_id, content=reply})
          │
          ▼
ChannelManager 的 feishu dispatcher 线程
  └─ FeishuChannel.send(msg)
          │
          ▼
飞书 API 回复用户
```

---

## 7.10 队列满时的行为

- **入站队列满** → 丢弃新消息，`error` 日志，`droppedInboundCount++`
- **出站队列满** → 丢弃新消息，`error` 日志，`droppedOutboundCount++`
- 不阻塞生产者，防止级联雪崩

调优建议：

- 高并发部署：`-Dtinyclaw.bus.inbound.queue.size=500`
- 慢通道单独观察：通过 Web 控制台 Channels 页面查看每个通道的 `getOutboundSize(channel)`

---

## 7.11 扩展：新增通道

见 [20 · 扩展开发](20-extending.md)。关键步骤：

1. 在 `ChannelsConfig` 增加配置内部类
2. 新建 `XxxChannel extends BaseChannel`
3. 实现 `start/stop/send/isAllowed`
4. 在 `ChannelManager.initChannels(...)` 中注册
5. 如需 webhook 回调，注册到 `WebhookServer`

---

## 7.12 下一步

- 想了解 LLM 层 → [08 · LLM 提供商](08-llm-providers.md)
- 想了解 Web 控制台如何与总线交互 → [17 · Web 控制台](17-web-console.md)
- 飞书/钉钉具体接入步骤 → `docs/feishu-guide.md` / `docs/dingtalk-guide.md`
