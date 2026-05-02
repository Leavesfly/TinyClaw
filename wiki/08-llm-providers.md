# 08 · LLM 提供商

> TinyClaw 如何把 9 家 LLM 服务统一成一个接口。

---

## 8.1 设计思路

TinyClaw 采用**单一抽象 + OpenAI 兼容协议**：

- 所有 Provider 通过 `LLMProvider` 接口暴露能力
- 默认实现 `HTTPProvider` 用 OkHttp 走 `POST /chat/completions`
- 新接一个 LLM，**改配置即可**，无需写 Java 代码
- 对于非标准接口（Anthropic / Gemini 原生），可在 `HTTPProvider` 内部做字段映射

---

## 8.2 LLMProvider 接口

```java
public interface LLMProvider {
    LLMResponse chat(List<Message> messages,
                     List<ToolDefinition> tools,
                     String model,
                     Map<String, Object> options) throws LLMException;

    void chatStream(List<Message> messages,
                    List<ToolDefinition> tools,
                    String model,
                    Map<String, Object> options,
                    StreamCallback callback) throws LLMException;

    interface StreamCallback {
        void onChunk(String delta);
        void onComplete(LLMResponse response);
        void onError(Throwable e);
    }

    interface EnhancedStreamCallback extends StreamCallback {
        void onToolCallStart(ToolCall toolCall);
        void onToolCallEnd(String toolCallId, String result);
        void onStreamEvent(StreamEvent event);
    }
}
```

---

## 8.3 关键数据类

| 类 | 作用 |
|----|------|
| `Message` | 角色 + 文本 + 可选 `tool_calls` / `tool_call_id` / `images` |
| `ToolDefinition` | 工具 JSON Schema，对应 OpenAI tool_choice 格式 |
| `ToolCall` | 模型发起的一次工具调用（id + name + arguments JSON） |
| `LLMResponse` | `content` + `toolCalls` + `usage`（token 统计） |
| `StreamEvent` | 统一的流式事件：`TEXT_DELTA` / `TOOL_CALL_START` / `TOOL_CALL_DELTA` / `TOOL_CALL_END` / `COLLAB_*` / `DONE` / `ERROR` |
| `LLMException` | 调用异常（网络/认证/限流/非法响应） |

---

## 8.4 HTTPProvider 实现

### 8.4.1 构造

```java
new HTTPProvider(providerName, apiKey, apiBase, httpClient);
```

- `providerName` 会参与日志与 token 统计（如 `dashscope` / `openai`）
- `apiBase` 通常以 `/v1` 结尾，内部会拼 `/chat/completions`
- 复用全局 OkHttpClient（连接池、HTTP/2、SSE）

### 8.4.2 请求构造（LLMRequestBuilder）

`LLMRequestBuilder` 负责把 `List<Message>` + `List<ToolDefinition>` 转成 OpenAI 兼容的 JSON：

```json
{
  "model": "qwen3.5-plus",
  "messages": [...],
  "tools": [...],
  "tool_choice": "auto",
  "temperature": 0.7,
  "max_tokens": 16384,
  "stream": true
}
```

关键适配：

- Anthropic：`system` 消息单独字段，工具调用字段名不同
- Gemini：`contents` 而非 `messages`
- Ollama：无需鉴权头
- 多模态：`images` 转成 OpenAI Vision 的 `content=[{type:"image_url",...}]`

### 8.4.3 响应解析（StreamResponseParser）

- 非流式：直接解析 JSON，取 `choices[0].message.content` 和 `tool_calls`
- 流式（SSE）：逐行解析 `data: {...}`，发布 `StreamEvent`
  - **增量 tool_calls**：工具参数会分段到达，用 `id` 聚合拼接完整 `arguments`
  - **DONE**：`data: [DONE]` 表示流结束

---

## 8.5 支持的 Provider 清单

| Provider | 配置字段 | API Base（默认） | 推荐模型 |
|----------|----------|------------------|----------|
| OpenRouter | `providers.openrouter` | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini`、`anthropic/claude-3.5-sonnet` |
| OpenAI | `providers.openai` | `https://api.openai.com/v1` | `gpt-4o`、`gpt-4o-mini` |
| Anthropic | `providers.anthropic` | `https://api.anthropic.com/v1` | `claude-3-5-sonnet-latest` |
| 智谱 GLM | `providers.zhipu` | `https://open.bigmodel.cn/api/paas/v4` | `glm-4`、`glm-4-flash` |
| Google Gemini | `providers.gemini` | `https://generativelanguage.googleapis.com/v1beta` | `gemini-1.5-pro` |
| 阿里云 DashScope | `providers.dashscope` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3.5-plus`、`qwen-max` |
| Groq | `providers.groq` | `https://api.groq.com/openai/v1` | `llama-3.1-70b`、`mixtral-8x7b` |
| Ollama（本地） | `providers.ollama` | `http://localhost:11434/v1` | `llama3`、`qwen2`、`mistral` |
| vLLM（本地） | `providers.vllm` | `http://localhost:8000/v1` | 任意 HF 模型 |

---

## 8.6 模型 → Provider 路由

`ModelsConfig.modelToProvider` 建立模型名到 Provider 的映射：

```json
{
  "models": {
    "default": "qwen3.5-plus",
    "modelToProvider": {
      "qwen3.5-plus": "dashscope",
      "glm-4-flash": "zhipu",
      "gpt-4o": "openai"
    }
  }
}
```

`ProviderManager.reloadModel()` 按以下顺序解析：

1. 使用 `AgentConfig.model` 作为目标模型（如未设置则用 `models.default`）
2. 解析 alias（`models.aliases` 映射 `fast` → `glm-4-flash`）
3. 在 `modelToProvider` 反查真正的 provider
4. 读 `providers.{provider}` 的 `apiKey` / `apiBase`
5. 构造 `HTTPProvider` 并原子替换 `ProviderComponents`

---

## 8.7 流式输出

### 8.7.1 启用条件

- 通道 `supportsStreaming() == true`（飞书、钉钉、MaixCam）
- 或直连的 Web 控制台 SSE / CLI 交互模式

### 8.7.2 StreamEvent 事件类型

| 类型 | 触发时机 |
|------|----------|
| `TEXT_DELTA` | 文本增量到达 |
| `TOOL_CALL_START` | 工具调用开始（拿到完整 name/arguments） |
| `TOOL_CALL_DELTA` | 工具参数增量（部分 Provider 会边生成边流） |
| `TOOL_CALL_END` | 工具执行完成（由 `ReActExecutor` 触发，不由 LLM） |
| `COLLAB_START` / `COLLAB_END` | 多 Agent 协同生命周期（由 `CollaborateTool` 触发） |
| `DONE` | 整轮结束 |
| `ERROR` | 发生错误 |

### 8.7.3 Tool Call 增量拼接

流式场景下多数 Provider 会分包发送 `arguments`：

```text
tool_calls[0]: {id:"call_1", name:"read_file", arguments:"{\"pa"}
tool_calls[0]: {arguments:"th\":\"a.txt\"}"}
```

`StreamResponseParser` 按 `index` 聚合这些片段，直到 `finish_reason=tool_calls` 才拼成完整 JSON。

---

## 8.8 Token 用量统计

`ReActExecutor` 在每次 LLM 调用后把 `LLMResponse.usage` 写入 `TokenUsageStore`：

- 统计维度：provider / model / 日期 / 会话 / 工具
- 存储：`workspace/token_usage.json`
- 查询：`token_usage` 工具（Agent 可调） + Web 控制台 Token Stats 页

---

## 8.9 错误处理

| 错误类型 | 处理策略 |
|----------|----------|
| 网络超时 / IO 异常 | 抛 `LLMException`，由 `ReActExecutor` 向上透传；工具循环中断 |
| 401 / 403 | 抛 `LLMException`，`MessageRouter` 会回复提示用户检查 API Key |
| 429 限流 | 抛 `LLMException`；未来可接入指数退避 |
| 空响应 | 最多重试 2 次，否则返回 `EMPTY_RESPONSE_FALLBACK` |
| Provider 未配置 | `AgentRuntime.isProviderConfigured()==false`，回复固定提示 |

---

## 8.10 本地模型（Ollama / vLLM）

### 8.10.1 Ollama

```bash
ollama pull llama3
ollama serve   # 默认 http://localhost:11434
```

配置：

```json
{
  "providers": {
    "ollama": { "apiKey": "", "apiBase": "http://localhost:11434/v1" }
  },
  "agent": { "model": "llama3" },
  "models": {
    "modelToProvider": { "llama3": "ollama" }
  }
}
```

注意：多数本地小模型**不支持** function calling，工具调用在本地模型上可能失效；建议搭配 `qwen2.5`、`llama3.1` 等带工具调用的版本。

### 8.10.2 vLLM

与 Ollama 类似，需要在启动时开启 OpenAI 兼容接口：

```bash
python -m vllm.entrypoints.openai.api_server --model Qwen/Qwen2.5-7B-Instruct
```

---

## 8.11 最佳实践

| 场景 | 推荐 |
|------|------|
| 国内网络 | 智谱 GLM + DashScope，`glm-4-flash` 做日常闲聊，`qwen-max` 做复杂任务 |
| 国际网络 | OpenRouter（一个 key 接多模型） |
| 追求速度 | Groq（Mixtral / Llama 极速推理） |
| 离线/隐私 | Ollama 本地 |
| 混合路由 | 配置多个模型 + 别名，用不同 session 使用不同模型 |

---

## 8.12 扩展：接入新 Provider

大多数场景只需**改配置**。若某个 Provider 的协议偏离 OpenAI 兼容：

1. 扩展 `HTTPProvider` 的 `LLMRequestBuilder`（加字段映射）
2. 如响应结构不同，扩展 `StreamResponseParser`
3. 极端情况下，直接实现一个全新的 `LLMProvider`

详见 [20 · 扩展开发](20-extending.md)。

---

## 8.13 下一步

- 工具调用如何落地 → [09 · 工具系统](09-tools-system.md)
- MCP 协议如何补充工具 → [10 · MCP 协议](10-mcp-integration.md)
- 运行时热切换模型 → [17 · Web 控制台](17-web-console.md)
