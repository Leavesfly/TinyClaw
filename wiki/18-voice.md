# 18 · 语音转写

> `voice/` 包：让语音消息也能成为 Agent 的输入。

---

## 18.1 能力

- 支持把音频消息转成文字后交给 Agent 处理
- 当前内置 `AliyunTranscriber`（阿里云 DashScope Paraformer-v2，**异步**模式）
- 接口化设计（`Transcriber`），可轻松扩展其他服务
- 装配时机：由 `GatewayBootstrap.initializeTranscriber()` 在**网关启动时**构造，只在 `providers.dashscope.apiKey` 非空时启用

典型场景：

- 用户在 **Telegram / Discord** 发送**语音消息**（当前仅这两个通道接入了 `setTranscriber(...)`）
- 通道实现识别出音频附件后下载 → 调用 `Transcriber.transcribe(path)`
- 转成文字后作为 `InboundMessage.content` 走标准流程

---

## 18.2 Transcriber 接口

```java
public interface Transcriber {
    TranscriptionResult transcribe(String audioFilePath) throws Exception;
    boolean isAvailable();
    String getProviderName();

    class TranscriptionResult {
        String text;       // 转录文本
        String language;   // 识别出的语言（如 "zh"、"en"）
        Double duration;   // 音频时长（秒）
    }
}
```

三个核心方法：

| 方法 | 语义 |
|------|------|
| `transcribe(path)` | 本地音频文件 → `TranscriptionResult` |
| `isAvailable()` | 检查配置是否齐全（主要是 API Key） |
| `getProviderName()` | 返回 provider 名（如 `aliyun`） |

---

## 18.3 AliyunTranscriber — DashScope Paraformer

### 18.3.1 协议

- 服务：DashScope 语音识别 REST API
  - `POST https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription`
  - 请求头 `X-DashScope-Async: enable`（异步模式）
- 步骤：
  1. 读取本地音频文件 → Base64 编码
  2. 以 `data:audio/{fmt};base64,...` 形式放入 `input.file_urls`
  3. 提交后拿到 `task_id`，调 `pollTranscriptionResult(taskId)` 每秒轮询一次
  4. `task_status = SUCCEEDED` 时解析结果，`FAILED` 抛异常，最多轮询 60 次（~60 秒）
- 请求模型：硬编码 `paraformer-v2`
- 语言提示：硬编码 `["zh", "en"]`（中英文混合识别）
- 格式识别：按文件扩展名映射为 `wav/mp3/ogg/aac/flac/pcm`，其他默认 `wav`（源码 `getAudioFormat()`）

### 18.3.2 配置

**当前版本完全由 `providers.dashscope.apiKey` 驱动**，无独立的 `voice` 配置段：

```json
{
  "providers": {
    "dashscope": {
      "apiKey": "sk-xxx"
    }
  }
}
```

- `apiKey` 非空 → 网关启动日志："Using Aliyun DashScope for voice transcription"
- `apiKey` 为空 → 网关启动日志："Voice transcription disabled: DashScope API key not configured"

如需切换模型、语言、超时等参数，目前需要修改 `AliyunTranscriber` 源码。

### 18.3.3 可用性判定

`AliyunTranscriber.isAvailable()`：

```java
return apiKey != null && !apiKey.isEmpty();
```

仅此一项检查。`GatewayBootstrap` 只在 `apiKey` 非空时才会 `new AliyunTranscriber(apiKey)` 并附加到通道。

### 18.3.4 OkHttp 客户端参数（硬编码）

```
connectTimeout = 60s
readTimeout    = 120s
writeTimeout   = 60s
```

---

## 18.4 通道集成

`GatewayBootstrap.initializeTranscriber()` 在构造完 `Transcriber` 后，通过 `attachTranscriberToChannel(...)` **仅将其附加到以下通道**：

| 通道 | 方法 |
|------|------|
| `TelegramChannel` | `telegramChannel.setTranscriber(transcriber)` |
| `DiscordChannel` | `discordChannel.setTranscriber(transcriber)` |

其他通道（飞书 / 钉钉 / WhatsApp / QQ / MaixCam）当前**未接入** `setTranscriber(...)`，收到语音消息的处理由各自通道实现决定。

通用骨架：

```text
Channel 收到 voice 消息
   │
   ▼
下载音频到本地临时文件
   │
   ▼
Transcriber.transcribe(path)
   │
   ▼
TranscriptionResult{text, language, duration}
   │
   ▼
InboundMessage{channel, chatId, content=text, ...}
   │
   ▼
MessageBus → Agent 走标准流程
```

---

## 18.5 转写异常与边界

`AliyunTranscriber.transcribe(...)` 的错误处理（来自源码）：

| 场景 | 行为 |
|------|------|
| 文件不存在 | 抛 `IOException("Audio file not found: ...")` |
| DashScope HTTP 非 2xx | 抛 `IOException("DashScope API error (status N): ...")` |
| 响应含 `code` 字段 | 抛 `IOException("DashScope error [code]: message")` |
| 轮询超过 60 次仍未完成 | 抛 `IOException("Transcription task timeout after 60 seconds")` |
| `task_status=FAILED` | 抛 `IOException("Transcription task failed: ...")` |
| 识别结果为空 | 文本回退为 `"[无法识别的音频]"`（不抛异常） |

异常处理 / 重试策略由**通道层**负责（每个通道实现自己决定收到异常后是否提示用户、是否重试），`AliyunTranscriber` 本身**不内置重试**。

日志命名空间：`voice`。

---

## 18.6 扩展：接入新转写服务

### 18.6.1 实现步骤

1. 新建 `voice/XxxTranscriber.java` 实现 `Transcriber`（返回唯一的 `providerName`）
2. 在 `GatewayBootstrap.initializeTranscriber()` 中按优先级顺序尝试构造：

```java
if (newProviderApiKey != null && !newProviderApiKey.isEmpty()) {
    transcriber = new XxxTranscriber(newProviderApiKey);
}
```

3. 继续通过 `attachTranscriberToChannel("telegram", TelegramChannel.class, transcriber)` 附加

> 注意：目前无配置项让用户自主选择 provider，需要修改 `GatewayBootstrap` 的装配逻辑。

### 18.6.2 把新 Transcriber 接入更多通道

如果要给非 Telegram / Discord 通道加语音支持：

1. 在目标通道类（如 `FeishuChannel`）中增加 `setTranscriber(Transcriber)` 字段
2. 在通道的消息处理逻辑里识别音频消息 → 下载 → 调 `transcriber.transcribe(path)`
3. 在 `GatewayBootstrap.initializeTranscriber()` 新增 `attachTranscriberToChannel("feishu", FeishuChannel.class, transcriber)`

---

## 18.7 TTS（文本转语音）

当前版本**未内置 TTS**（文本转语音）。若需要"Agent 回复语音"，需要自行实现：

- 在回复路径里增加一个 TTS 组件（例如调 DashScope Sambert）
- 返回给通道前把文本 → 音频文件 → 发送音频
- 推荐做法：包装成一个**工具**（如 `speak_tts`）供 Agent 按需调用，而不是每条都自动转语音

---

## 18.8 最佳实践

| 建议 | 原因 |
|------|------|
| 确保 `providers.dashscope.apiKey` 已配置 | 否则 `initializeTranscriber()` 会打印 "Voice transcription disabled" |
| 转写结果校对提示 | 在 Agent 系统提示中加一条："用户消息可能来自语音识别，如有歧义请主动澄清" |
| 敏感场景禁用 | 医疗/法律等场景关闭，避免识别误差导致严重后果 |
| 长音频注意 60 秒轮询上限 | 超过会抛 timeout 异常，需通道层兜底或改源码延长 |
| 隐私数据处理 | 转写完成后由通道层按需删除音频临时文件 |

---

## 18.9 下一步

- 通道集成细节 → [07 · 消息总线与通道](07-message-bus-and-channels.md)
- 文件上传与存储 → [17 · Web 控制台 §17.9](17-web-console.md)
- 扩展 Transcriber → [20 · 扩展开发](20-extending.md)
