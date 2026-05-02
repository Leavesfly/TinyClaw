# 20 · 扩展开发指南

> 所有你可能想"二次开发"的扩展点，都在这里。

TinyClaw 被刻意设计成**接口优先**：每个子系统都有一个 `interface`，所有内置实现只是参考实现之一。本文把常见扩展场景的**扩展点、接口签名、装配入口**一次性列清楚。

---

## 20.1 扩展点全景

| 类别 | 接口 | 装配入口 | 文档 |
|------|------|----------|------|
| LLM 提供商 | `providers.LLMProvider` | `ProviderManager` | [08](08-llm-providers.md) |
| 消息通道 | `channels.Channel` | `ChannelManager` | [07](07-message-bus-and-channels.md) |
| 工具 | `tools.Tool`（可叠加 `StreamAwareTool`、`ToolContextAware`） | `ToolRegistry.register` | [09](09-tools-system.md) |
| MCP 客户端 | `mcp.MCPClient` | `MCPManager` | [10](10-mcp-integration.md) |
| 协同策略 | `collaboration.strategy.CollaborationStrategy` | `AgentOrchestrator` | [11](11-multi-agent-collaboration.md) |
| 优化策略 | `evolution.strategy.OptimizationStrategy` | `PromptOptimizer` | [12](12-self-evolution.md) |
| 钩子 handler | `hooks.HookHandler` | `HookConfigLoader` | [19](19-hooks.md) |
| 语音转写 | `voice.Transcriber` | `GatewayBootstrap.initializeTranscriber` | [18](18-voice.md) |
| Web Handler | 自定义类 | `WebConsoleServer.registerApiEndpoints` | [17](17-web-console.md) |
| 技能 | Markdown 文件 | `SkillsLoader` | [13](13-skills-system.md) |

---

## 20.2 新增 LLM 提供商

### 20.2.1 接口

```java
public interface LLMProvider {
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools,
                     String model, Map<String, Object> options);

    LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools,
                           String model, Map<String, Object> options,
                           StreamCallback callback);

    String getDefaultModel();
    String getName();
}
```

### 20.2.2 推荐路径

绝大多数云端 LLM 都是 OpenAI 兼容协议，直接复用 `HTTPProvider` 即可：

```java
LLMProvider custom = new HTTPProvider(
    "my-provider",        // providerName，会出现在日志与 token 统计
    apiKey,
    "https://api.example.com/v1",
    httpClient);          // 可传项目共享的 OkHttpClient
```

**不要**自己重新实现流式 SSE 解析、`tool_calls` 聚合等 — `HTTPProvider` 已经处理好。

### 20.2.3 非 OpenAI 兼容协议

若目标服务协议差异大（如 Anthropic 原生 API），写一个独立实现：

```java
public class AnthropicNativeProvider implements LLMProvider {
    public String getName() { return "anthropic-native"; }
    public String getDefaultModel() { return "claude-3-5-sonnet"; }
    // ...
}
```

### 20.2.4 装配

在 `ProviderManager.applyProvider(...)` 的分支里按 `providers.{name}` 配置 new 出来并注册。支持多 Provider 并存后，模型路由由 `models.modelProviders` 决定。

---

## 20.3 新增消息通道

### 20.3.1 接口

```java
public interface Channel {
    String name();
    void start();
    void stop();
    void send(OutboundMessage message);
    boolean isRunning();
    boolean isAllowed(String senderId);

    default boolean supportsStreaming() { return false; }
    default LLMProvider.StreamCallback createStreamingCallback(String chatId) { return null; }
}
```

### 20.3.2 推荐做法

继承 `BaseChannel`，它封装了白名单、启停状态、日志、MessageBus 投递等公共逻辑，你只需要关注**协议对接**：

```java
public class SlackChannel extends BaseChannel {
    public SlackChannel(SlackConfig cfg, MessageBus bus) {
        super("slack", cfg.getAllowList(), bus);
    }

    @Override
    public void start() {
        // 建立 Slack WebSocket 连接
        // 收到消息 → submitInbound(new InboundMessage(...))
    }

    @Override
    public void stop() { /* 断开连接 */ }

    @Override
    public void send(OutboundMessage message) {
        // 调 Slack API 发送
    }
}
```

### 20.3.3 装配

1. 在 `ChannelsConfig` 加对应字段（`slack: SlackConfig`）
2. 在 `ChannelManager.createChannels()` 按配置 new 并注册
3. 在 `WebUtils.CHANNEL_SLACK` 增加常量（可选）
4. Web 控制台 `ChannelsHandler` 即可识别管理

### 20.3.4 支持流式输出

若协议支持边生成边推送（Telegram `editMessageText` 就能做到），覆写：

```java
@Override
public boolean supportsStreaming() { return true; }

@Override
public LLMProvider.StreamCallback createStreamingCallback(String chatId) {
    return chunk -> pushChunkToChat(chatId, chunk);
}
```

---

## 20.4 新增工具

### 20.4.1 基础工具

```java
public class NowTool implements Tool {
    public String name() { return "now"; }
    public String description() { return "返回当前时间（ISO-8601）"; }

    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of()
        );
    }

    public String execute(Map<String, Object> args) {
        return Instant.now().toString();
    }
}
```

注册：

```java
agentRuntime.registerTool(new NowTool());
```

### 20.4.2 流式工具

需要把中间结果实时推送给上游（如长耗时查询）：

```java
public class SlowSearchTool implements Tool, StreamAwareTool {
    private LLMProvider.EnhancedStreamCallback callback;

    public void setStreamCallback(LLMProvider.EnhancedStreamCallback cb) {
        this.callback = cb;
    }

    public String execute(Map<String, Object> args) {
        for (Result r : search(args)) {
            if (callback != null) {
                callback.onEvent(StreamEvent.content("找到：" + r.title + "\n"));
            }
        }
        return "共找到 N 条结果";
    }
    // name/description/parameters 省略
}
```

### 20.4.3 需要通道上下文的工具

（如 `message` / `cron` 这种需要知道"把结果发到哪"的工具）：

```java
public class NotifyTool implements Tool, ToolContextAware {
    private String channel;
    private String chatId;

    public void setChannelContext(String channel, String chatId) {
        this.channel = channel;
        this.chatId = chatId;
    }

    public String execute(Map<String, Object> args) {
        // 可以用 channel + chatId 通过 MessageBus 主动推送
        return "ok";
    }
}
```

### 20.4.4 JSON Schema 写法

`parameters()` 必须是合法 JSON Schema `object`：

```java
Map.of(
    "type", "object",
    "properties", Map.of(
        "query", Map.of("type", "string", "description", "搜索词"),
        "limit", Map.of("type", "integer", "default", 10)
    ),
    "required", List.of("query")
)
```

---

## 20.5 新增 MCP 服务器

**纯配置驱动**，不改代码。见 [10 · MCP 协议](10-mcp-integration.md) §10.13。

如果是自己**实现新的 MCPClient 传输**（极少见），需要实现 `MCPClient` 接口并在 `MCPManager.initializeServer(...)` 的分支里增加识别。

---

## 20.6 新增协同策略

### 20.6.1 接口

```java
public interface CollaborationStrategy {
    String execute(SharedContext context, List<RoleAgent> agents, CollaborationConfig config);
    boolean shouldTerminate(SharedContext context, CollaborationConfig config);
    String getName();
    default String getDescription() { return getName(); }
}
```

### 20.6.2 实现要点

- `execute(...)` 是阻塞调用，返回最终结论字符串
- 每轮向 `SharedContext` 追加消息、Artifact、调整 `consensusScore`
- 尊重 `config.tokenBudget` 与 `config.timeoutMs`，超出立即 break
- 若实现为流式，通过 `CollaborationConfig` 的回调把进度 `StreamEvent` 送出

### 20.6.3 装配

`AgentOrchestrator.initStrategies()` 中注册到 mode → strategy 的映射表，并在 `CollaborationConfig.Mode` 枚举（或等价入口）加上新模式值。

---

## 20.7 新增 Prompt 优化策略

### 20.7.1 接口

```java
public interface OptimizationStrategy {
    OptimizationResult optimize(String currentPrompt, OptimizationContext context);
    String name();
    default boolean canOptimize(OptimizationContext context) { return true; }
}
```

### 20.7.2 设计要点

- 从 `context` 获取反馈、历史变体、LLM 调用能力
- 返回 `OptimizationResult`：新 prompt、改进原因、评分（可选）；无需优化时返回 `noImprovementNeeded`
- **避免**直接修改 `context`，保持幂等

### 20.7.3 装配

在 `PromptOptimizer.initStrategies()` 中把新策略加入 `Map<String, OptimizationStrategy>`，然后 `agent.evolution.strategy` 配置该 `name()` 即可启用。

---

## 20.8 新增 Hook Handler（进阶）

默认只支持 `type = "command"`（外部脚本）。若要添加如 `webhook` / `http` / `java` 等新类型：

### 20.8.1 实现 HookHandler

```java
public class WebhookHookHandler implements HookHandler {
    private final String url;
    private final long timeoutMs;

    public WebhookHookHandler(String url, long timeoutMs) {
        this.url = url;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public HookDecision invoke(HookContext ctx) {
        try {
            String json = MAPPER.writeValueAsString(ctx.toPayload());
            Response resp = httpClient.newCall(/* POST url + json */).execute();
            return parseResponse(resp);   // 同 CommandHookHandler 的 JSON 解析
        } catch (Exception e) {
            return HookDecision.cont();   // fail-open
        }
    }
}
```

**重要约束**：`invoke(...)` 绝不能抛出异常；所有异常捕获并返回 `cont()`。

### 20.8.2 扩展 HookConfigLoader

在 `HookConfigLoader.parseHandler(event, handlerNode)` 中给 `type` 加一个分支：

```java
if ("webhook".equalsIgnoreCase(type)) {
    String url = handlerNode.path("url").asText(null);
    long timeoutMs = handlerNode.path("timeoutMs").asLong(0L);
    return new WebhookHookHandler(url, timeoutMs);
}
```

### 20.8.3 使用

```json
{"type": "webhook", "url": "https://hooks.example.com/audit", "timeoutMs": 3000}
```

---

## 20.9 新增语音转写 Provider

### 20.9.1 接口

```java
public interface Transcriber {
    TranscriptionResult transcribe(String audioFilePath) throws Exception;
    boolean isAvailable();
    String getProviderName();
}
```

### 20.9.2 实现

参考 `AliyunTranscriber`（`voice/AliyunTranscriber.java`）。

### 20.9.3 装配

修改 `GatewayBootstrap.initializeTranscriber()`，按优先级顺序构造，例如：

```java
if (transcriber == null && groqApiKey != null && !groqApiKey.isEmpty()) {
    transcriber = new GroqTranscriber(groqApiKey);
    logger.info("Using Groq for voice transcription");
}
```

最后仍通过 `attachTranscriberToChannel(...)` 附加到目标通道。

---

## 20.10 新增 Web Handler

### 20.10.1 实现

```java
public class MyHandler {
    private final Config config;
    private final SecurityMiddleware security;

    public MyHandler(Config config, SecurityMiddleware security) {
        this.config = config;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;

        String corsOrigin = config.getGateway().getCorsOrigin();
        try {
            // 业务逻辑
            ObjectNode result = WebUtils.MAPPER.createObjectNode();
            result.put("hello", "world");
            WebUtils.sendJson(exchange, 200, result, corsOrigin);
        } catch (Exception e) {
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }
}
```

### 20.10.2 装配

1. 在 `WebUtils` 加 API 常量：`public static final String API_MY = "/api/my";`
2. 在 `WebConsoleServer.registerApiEndpoints(...)` 注册：
   ```java
   httpServer.createContext(WebUtils.API_MY, new MyHandler(config, security)::handle);
   ```

### 20.10.3 鉴权与 CORS

`security.preCheck()` 已处理 CORS 预检 + Basic Auth + 速率限制，直接复用即可。

---

## 20.11 新增内置技能

**纯配置**：在 `src/main/resources/skills/{name}/SKILL.md` 新建 Markdown 文件，重新打包 JAR 后用户可以 `tinyclaw skills install-builtin` 一键装入。

格式见 [13 · 技能系统](13-skills-system.md) §13.3。

---

## 20.12 通用扩展建议

| 建议 | 原因 |
|------|------|
| **接口小而稳**：优先实现已有接口而不是改动核心 | 降低升级冲突 |
| **异常内化**：绝不让扩展抛到主流程 | 保持 Agent 鲁棒 |
| **线程安全**：所有扩展都可能被并发调用 | `ToolRegistry` / `MessageBus` 都是并发的 |
| **日志命名空间**：用专属 logger（如 `LoggerFactory.getLogger("my-ext")`） | 便于过滤排查 |
| **配置热重载**：尽量无状态或支持 `close() / init()` 重建 | 对齐 `ProviderManager.reload()` 能力 |
| **测试先行**：为每个扩展写单元测试 | 集成测试成本高 |

---

## 20.13 发布与分发

TinyClaw 暂不支持独立**扩展 JAR 热加载**（没有插件类加载器），目前扩展方式：

- 方式 A：Fork 仓库，在源码树内增加实现，重新打包
- 方式 B：把实现做成独立 MCP Server（不占 JVM），零侵入对接

对于大多数场景，方式 B 更推荐。

---

## 20.14 下一步

- 遇到问题 → [21 · FAQ](21-faq-troubleshooting.md)
- 配置参考 → [04 · 配置指南](04-configuration.md)
- 安全约束 → [16 · 安全沙箱](16-security-sandbox.md)
