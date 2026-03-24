# TinyClaw 技术分享：从个人 AI 助手到多 Agent 协同平台

> 🦞 **TinyClaw** — 超轻量个人 AI 助手框架
> 版本：0.1.0 ｜ 最后更新：2026-03-24

---

## 目录

- [一、项目背景与定位](#一项目背景与定位)
- [二、设计哲学](#二设计哲学)
- [三、整体架构](#三整体架构)
- [四、核心引擎深度解析](#四核心引擎深度解析)
- [五、多 Agent 协同编排](#五多-agent-协同编排)
- [六、自我进化引擎](#六自我进化引擎)
- [七、MCP 协议集成](#七mcp-协议集成)
- [八、安全体系](#八安全体系)
- [九、Web 控制台](#九web-控制台)
- [十、与同类框架对比](#十与同类框架对比)
- [十一、Demo 演示指南](#十一demo-演示指南)
- [十二、未来展望](#十二未来展望)

---

## 一、项目背景与定位

### 为什么要做 TinyClaw？

AI Agent 领域正在经历从"能用"到"好用"的转变。现有的 Agent 框架面临几个共性问题：

| 痛点 | 表现 |
|------|------|
| **部署复杂** | 依赖 Python/Node 生态，环境配置繁琐 |
| **安全缺失** | 缺乏内置沙箱，Agent 可执行任意危险操作 |
| **单 Agent 局限** | 复杂任务需要多角色协作，单 Agent 力不从心 |
| **无法自我改进** | Agent 不能从历史交互中学习和进化 |
| **企业不友好** | Python/Node 在企业 IT 环境中信任度低 |

TinyClaw 的目标是提供一个**生产级、可进化、支持多 Agent 协同**的轻量 AI 助手框架。

### 核心定位

```text
┌─────────────────────────────────────────────────┐
│                TinyClaw 定位                     │
│                                                  │
│  轻量化 ──── 单 JAR 部署，无需重型框架            │
│  生产级 ──── 安全沙箱 + 多层防护 + 持久化         │
│  可进化 ──── Prompt 自动优化 + 记忆进化           │
│  多 Agent ── 7 种协同模式 + 工作流引擎            │
│  多通道 ──── 7+ IM 平台开箱即用                   │
│  可扩展 ──── MCP 协议 + 技能插件 + 工具注册       │
└─────────────────────────────────────────────────┘
```

### 技术选型：为什么是 Java？

- **生态成熟**：丰富的第三方 SDK（Telegram、Discord、飞书、钉钉等），无需造轮子
- **跨平台**：一次编译到处运行，从个人电脑到服务器到嵌入式设备
- **企业友好**：Java 在企业环境中被广泛接受，易于集成到现有技术栈
- **并发优势**：原生多线程 + 成熟的并发工具类，天然适合多通道、多 Agent 场景
- **运行时稳定**：JVM 成熟的 GC 机制和异常处理，适合 7×24 长期运行

---

## 二、设计哲学

### 2.1 UNIX 哲学的现代演绎

**"Do One Thing Well" + 组合优于继承**

TinyClaw 将 Agent 能力分解为独立的工具（Tool），每个工具只做一件事，LLM 作为"Shell"动态编排工具组合：

```text
UNIX 管道：  cat file.txt | grep "error" | wc -l
TinyClaw：   LLM → ReadFileTool → (推理) → WebSearchTool → (推理) → 最终答案
```

### 2.2 六边形架构（端口与适配器）

Agent 核心引擎与外部世界完全解耦：

```text
                    ┌─────────────────┐
  Telegram ────►    │                 │ ────► OpenAI
  Discord  ────►    │   Agent 引擎    │ ────► Anthropic
  飞书     ────►    │  (核心领域)     │ ────► 智谱 GLM
  钉钉     ────►    │                 │ ────► Ollama
  CLI      ────►    │                 │ ────► MCP Server
                    └─────────────────┘
                     ▲               ▲
                     │               │
                  MessageBus    ToolRegistry
                 (入站端口)     (出站端口)
```

- 更换消息平台无需修改 Agent 代码
- 切换 LLM 提供商只需修改配置
- 添加新工具不影响现有功能

### 2.3 反应式架构

MessageBus 采用事件驱动 + 异步消息传递：

- **非阻塞**：`LinkedBlockingQueue` 实现生产者-消费者解耦
- **背压处理**：队列满时丢弃消息，防止级联故障
- **故障隔离**：单个通道故障不影响其他通道
- **弹性扩展**：各通道独立吞吐

### 2.4 分层防御纵深

借鉴信息安全领域的纵深防御理论：

```text
外部输入 → 通道白名单（身份验证）
         → SecurityGuard 路径检查（工作空间沙箱）
         → SecurityGuard 命令检查（命令黑名单）
         → 工具执行（资源限制）
         → 日志审计（事后追溯）
```

每层独立生效，即使某层被绕过，其他层仍能提供保护。

### 2.5 递归自我扩展

Agent 不仅是执行者，更是能力构建者：

- **技能自创建**：通过 `SkillsTool` 创建新技能固化经验
- **Prompt 自优化**：通过 `PromptOptimizer` 自动改进系统提示
- **记忆自进化**：通过 `MemoryEvolver` 从对话中提取长期记忆
- **社区能力获取**：从 GitHub 按需安装技能

这实现了**"元能力"——创建能力的能力**。

---

## 三、整体架构

### 3.1 六层架构

```text
┌──────────────────────────────────────────────────────┐
│                 CLI & Gateway 入口层                   │
│  TinyClaw.java + 8 个 CliCommand                      │
└──────────────────────────┬───────────────────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
      ┌─────────────┐  ┌─────────┐  ┌────────────────┐
      │ Agent 引擎   │  │ 网关服务 │  │ Web 控制台      │
      │ AgentLoop    │  │ Gateway  │  │ WebConsoleServer│
      │ MessageRouter│  │ Bootstrap│  │ + 16 Handlers  │
      │ ProviderMgr  │  └────┬────┘  └────┬───────────┘
      └──────┬───────┘       │            │
             ▼               │            │
     ┌─────────────────────────────────────────────┐
     │             消息总线 MessageBus              │
     └────────┬──────────────────────┬─────────────┘
              │                      │
              ▼                      ▼
     ┌─────────────────┐    ┌──────────────────────┐
     │ LLM Provider    │    │ 消息通道（7 种）       │
     │ + ProviderMgr   │    │ Telegram / Discord /  │
     └────────┬────────┘    │ 飞书 / 钉钉 / ...     │
              │             └──────────────────────┘
       ┌──────┴──────┐
       ▼             ▼
┌────────────┐ ┌───────────┐
│ 工具系统    │ │ MCP 集成   │
│ 15 内置工具 │ │ 3 种传输   │
└──────┬─────┘ └───────────┘
       │
  ┌────┴──────────────────────────────────┐
  │            高级能力层                   │
  ├──────────────┬──────────────┬──────────┤
  │ 多Agent协同   │ 自我进化引擎  │ 技能系统  │
  │ 7种模式       │ 3种优化策略   │ 语义搜索  │
  │ 工作流引擎    │ 记忆进化      │ GitHub安装│
  └──────────────┴──────────────┴──────────┘
       │
  ┌────┴──────────────────────────────────┐
  │           基础设施层                   │
  │ Config / Session / Security / Logger  │
  │ Cron / Heartbeat / Voice / Util       │
  └───────────────────────────────────────┘
```

### 3.2 模块统计

| 层次 | 模块数 | 核心类数 | 说明 |
|------|--------|----------|------|
| 入口层 | 2 | 10 | CLI 命令 + 应用入口 |
| Agent 引擎层 | 4 | 22 | 核心引擎 + 上下文 + 进化 + 协同 |
| 通信层 | 3 | 20 | 消息总线 + 7 通道 + Provider |
| 工具层 | 2 | 30 | 15 内置工具 + MCP 集成 |
| 高级能力层 | 3 | 35+ | 协同策略 + 工作流 + 进化 + 技能 |
| 基础设施层 | 8 | 20+ | 配置 + 会话 + 安全 + 日志等 |

---

## 四、核心引擎深度解析

### 4.1 Agent 引擎的职责分离

TinyClaw 的 Agent 引擎经过重构，采用**职责分离**设计，将原来集中在 AgentLoop 中的逻辑拆分为多个专职组件：

```text
AgentLoop（生命周期管理）
    │
    ├── MessageRouter（消息路由）
    │       ├── routeUser()     → 用户消息 → LLM 处理
    │       ├── routeCommand()  → 指令消息 → 直接执行
    │       └── routeSystem()   → 系统消息 → 路由回原始会话
    │
    ├── ProviderManager（Provider 管理）
    │       ├── setProvider()   → 动态注入
    │       ├── reloadModel()   → 热重载
    │       └── applyProvider() → 构建所有派生组件
    │
    ├── ContextBuilder（分段式上下文构建）
    │       ├── IdentitySection   → Agent 身份
    │       ├── BootstrapSection  → 基础行为
    │       ├── ToolsSection      → 工具摘要
    │       ├── SkillsSection     → 技能摘要（语义匹配）
    │       └── MemorySection     → 长期记忆
    │
    └── ProviderComponents（组件容器）
            ├── LLMExecutor       → LLM 调用 + 工具迭代
            ├── SessionSummarizer → 会话摘要
            ├── MemoryEvolver     → 记忆进化
            ├── FeedbackManager   → 反馈收集
            ├── PromptOptimizer   → Prompt 优化
            └── AgentOrchestrator → 多 Agent 协同
```

### 4.2 消息处理流程

```text
用户消息到达
    │
    ▼
MessageRouter.route()
    │
    ├── isCommand? → routeCommand() → 执行指令（如 /new 创建新会话）
    │
    ├── channel=system? → routeSystem() → 解析原始来源 → LLM 处理 → 回复原始通道
    │
    └── 普通消息 → routeUser()
            │
            ▼
        ContextBuilder.buildMessages()
        （组装系统提示 + 历史 + 当前消息）
            │
            ▼
        isStreamingChannel?
            ├── Yes → LLMExecutor.executeStream()
            └── No  → LLMExecutor.execute()
                        │
                        ▼
                   LLM 返回
                        │
                   有 tool_calls?
                        ├── Yes → ToolRegistry.execute() → 追加结果 → 再次调用 LLM
                        │         （最多 maxIterations 次）
                        └── No  → 返回文本回复
            │
            ▼
        persistAndSummarize()
        （保存会话 + 按需触发摘要 + 记忆进化）
            │
            ▼
        publishReplyIfNeeded()
        （通过 MessageBus 发布到出站队列）
```

### 4.3 Provider 热重载机制

ProviderManager 支持运行时动态切换模型和 Provider：

```text
reloadModel()
    │
    ▼
resolveProviderName(modelName)
    │  从 ModelsConfig 反查 model 对应的 provider
    │  若未定义则 fallback 到 AgentConfig.provider
    │
    ▼
ProvidersConfig.getByName(providerName)
    │  获取 API Key 和 API Base
    │
    ▼
new HTTPProvider(apiKey, apiBase)
    │
    ▼
applyProvider(newProvider)
    │  一次性重建所有派生组件：
    │  LLMExecutor / SessionSummarizer / MemoryEvolver /
    │  FeedbackManager / PromptOptimizer / AgentOrchestrator
    │
    ▼
providerConfigured = true
```

使用 `volatile` + `synchronized` 组合保证线程安全，支持通过 Web 控制台在运行时切换模型。

### 4.4 分段式上下文构建

ContextBuilder 采用 `ContextSection` 接口实现模块化的系统提示组装：

```java
public interface ContextSection {
    String build(SectionContext context);
}
```

每个 Section 独立负责一段内容的生成，ContextBuilder 按序组装：

1. **IdentitySection**：读取 AGENTS.md / SOUL.md / USER.md / IDENTITY.md，如果 PromptOptimizer 有活跃的优化版本则使用优化版
2. **BootstrapSection**：注入基础行为指令、当前时间、通道信息
3. **ToolsSection**：从 ToolRegistry 获取工具摘要
4. **SkillsSection**：从 SkillsLoader 获取技能摘要，支持基于用户输入的**语义搜索匹配**，只注入相关技能
5. **MemorySection**：从 MemoryStore 加载长期记忆

这种设计使得每个段落可以独立测试、替换和扩展。

### 4.5 会话摘要与记忆进化

SessionSummarizer 在后台异步执行，不阻塞主消息处理：

```text
maybeSummarize(sessionKey)
    │
    ├── 消息数 > 阈值？
    ├── Token 数 > 上下文窗口 × 百分比？
    │
    ▼ (满足任一条件)
异步线程执行：
    1. 保留最近 N 条消息
    2. 对较早消息分批摘要
    3. 与已有摘要 merge
    4. 触发 MemoryEvolver.evolve()
       → 从对话中提取长期记忆
       → 持久化到 MemoryStore
```

---

## 五、多 Agent 协同编排

### 5.1 为什么需要多 Agent？

单个 Agent 在面对复杂任务时存在局限：

| 场景 | 单 Agent 的问题 | 多 Agent 的优势 |
|------|-----------------|-----------------|
| 方案评审 | 缺乏对立视角 | 正反方辩论，全面分析 |
| 复杂项目 | 上下文过长，质量下降 | 子任务分解，并行执行 |
| 决策制定 | 单一视角偏见 | 多角色投票，共识决策 |
| 流程执行 | 难以管理多步骤依赖 | 工作流引擎，自动编排 |

### 5.2 协同架构

```text
CollaborateTool（工具入口）
       │
       ▼
AgentOrchestrator（编排器）
       │
       ├── 解析 CollaborationConfig
       ├── 创建 SharedContext（共享上下文）
       ├── 创建 AgentExecutor 列表（每个角色一个）
       │
       ▼
CollaborationStrategy（策略选择）
       │
       ├── DiscussionStrategy   → debate / roleplay / consensus
       ├── TeamWorkStrategy     → team
       ├── HierarchyStrategy    → hierarchy
       ├── WorkflowStrategy     → workflow
       └── DynamicRoutingStrategy → dynamic
       │
       ▼
执行协同流程
       │
       ├── 保存 CollaborationRecord
       ├── 结论回流到主会话
       └── 反馈到进化系统
```

### 5.3 七种协同模式详解

#### 模式一：Debate（辩论）

正反方观点对决，适合利弊权衡和方案评审。

```text
用户问题: "微服务 vs 单体架构，哪个更适合我们的项目？"

Agent A（微服务倡导者）──► 阐述微服务优势
                              │
Agent B（单体倡导者）  ──► 反驳并阐述单体优势
                              │
Agent A ──► 回应反驳，补充论据
                              │
Agent B ──► 最终反驳
                              │
LLM 汇总 ──► 综合结论
```

#### 模式二：Team（团队协作）

任务分解为子任务，支持并行和串行执行。

```text
用户任务: "为新产品编写完整的技术方案"

TeamWorkStrategy:
  ├── 子任务 1: 需求分析（Agent A）  ──┐
  ├── 子任务 2: 技术选型（Agent B）  ──┤ 并行执行
  ├── 子任务 3: 架构设计（Agent C）  ──┘
  │
  └── 汇总: 整合所有子任务结果
```

#### 模式三：Roleplay（角色扮演）

多角色对话模拟，适合用户访谈和场景演练。

#### 模式四：Consensus（共识决策）

多方讨论后投票达成共识，支持配置共识阈值（默认 0.6）。

#### 模式五：Hierarchy（层级决策）

层级汇报式决策，逐层汇总分析，适合组织结构化的决策流程。

#### 模式六：Workflow（工作流）

多步骤工作流，支持复杂依赖关系。**支持 LLM 动态生成工作流定义**。

```text
WorkflowDefinition
    │
    ├── WorkflowNode (SINGLE)      → 单 Agent 执行
    ├── WorkflowNode (PARALLEL)    → 多 Agent 并行
    ├── WorkflowNode (SEQUENTIAL)  → 多 Agent 串行
    ├── WorkflowNode (CONDITIONAL) → 条件分支路由
    ├── WorkflowNode (LOOP)        → 循环执行
    └── WorkflowNode (AGGREGATE)   → 聚合多个结果
```

WorkflowEngine 支持：
- **依赖解析**：自动按依赖顺序执行节点
- **条件分支**：根据条件表达式路由到不同节点
- **循环执行**：支持迭代直到满足退出条件
- **超时与重试**：节点级别的超时和重试配置
- **变量传递**：通过 `${nodeId.result}` 表达式引用其他节点的输出

#### 模式七：Dynamic（动态路由）

开放式协作，由 Router Agent 根据上下文动态选择下一个发言者。

### 5.4 协同增强特性

| 特性 | 说明 |
|------|------|
| **Token 预算** | 设置 Token 上限，超出后自动终止协同 |
| **优雅降级** | 协同失败时自动降级为单 Agent 模式 |
| **自反馈循环** | Critic Agent 评估结果质量，不合格则改进重试 |
| **协同记录** | 自动保存到 `workspace/collaboration/` |
| **结论回流** | 协同结论自动回流到调用方的主会话历史 |
| **反馈集成** | 协同结果可驱动 Agent 自我进化 |
| **上下文注入** | 主 Agent 的对话摘要可注入协同上下文 |

---

## 六、自我进化引擎

### 6.1 进化理念

传统 Agent 的能力在部署后就固定了。TinyClaw 的进化引擎让 Agent 能**持续从交互中学习和改进**：

```text
                    ┌──────────────┐
                    │   用户交互    │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │  反馈收集     │ ← FeedbackManager
                    │ (显式+隐式)   │
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
      ┌──────────┐  ┌───────────┐  ┌──────────┐
      │ Prompt   │  │ 记忆进化   │  │ 技能创建  │
      │ 优化     │  │ Memory    │  │ Skills   │
      │ 3种策略   │  │ Evolver   │  │ Tool     │
      └──────────┘  └───────────┘  └──────────┘
              │            │            │
              ▼            ▼            ▼
      ┌──────────┐  ┌───────────┐  ┌──────────┐
      │ 更好的    │  │ 更丰富的   │  │ 更多的   │
      │ 系统提示  │  │ 长期记忆   │  │ 技能     │
      └──────────┘  └───────────┘  └──────────┘
```

### 6.2 Prompt 自动优化 — 三种策略

#### 策略一：Textual Gradient（文本梯度）

灵感来自深度学习的梯度下降，但应用于文本空间：

```text
核心公式：Prompt(t+1) = O(Prompt(t), E)

1. 收集近期 EvaluationFeedback
2. 分析反馈中的问题模式 → 生成"文本梯度"（优化建议）
3. 将梯度应用到当前 Prompt → 生成优化版本
4. 保存为候选变体，待评估后决定是否采用
```

#### 策略二：OPRO（历史轨迹引导）

基于 Google DeepMind 的 OPRO 论文思想：

```text
1. 维护历史 Prompt 变体及其评分轨迹
2. 分析趋势：哪些改动带来提升？哪些导致下降？
3. 基于历史轨迹生成新的、更优的 Prompt
4. 避免重复已知的失败方向
```

#### 策略三：Self-Refine（自我反思）

不依赖外部反馈，Agent 自我审视：

```text
1. 回顾最近的会话交互记录
2. 从帮助性、准确性、简洁性、工具使用、主动性五个维度自我评估
3. 生成改进建议
4. 将建议应用到 Prompt
```

#### Prompt 变体管理

```text
{workspace}/evolution/prompts/
├── PROMPT_VARIANTS.json    # 所有变体及其评分
├── PROMPT_ACTIVE.md        # 当前活跃的优化 Prompt
└── PROMPT_HISTORY/         # 历史版本归档
```

优化器支持冷却期配置，避免过于频繁的优化。

### 6.3 记忆进化

`MemoryEvolver` 在会话摘要完成后自动触发：

```text
会话摘要完成
    │
    ▼
MemoryEvolver.evolve()
    │
    ├── 分析对话内容
    ├── 提取有价值的长期记忆
    │   （用户偏好、重要事实、经验教训等）
    │
    ▼
MemoryStore.save()
    │
    └── 持久化到 workspace/memory/MEMORY.md
```

记忆在后续对话中通过 `MemorySection` 注入系统提示，使 Agent 能"记住"重要信息。

### 6.4 反馈收集

`FeedbackManager` 支持多种反馈来源：

| 反馈类型 | 来源 | 说明 |
|----------|------|------|
| 显式评分 | Web 控制台 / FeedbackHandler | 用户主动评分（0-5） |
| 显式评论 | Web 控制台 | 用户文字反馈 |
| 隐式信号 | 消息交换记录 | 对话轮次、工具调用成功率等 |
| 协同反馈 | AgentOrchestrator | 协同任务的成功/失败 |

---

## 七、MCP 协议集成

### 7.1 什么是 MCP？

**MCP（Model Context Protocol）** 是一个开放协议，允许 AI 模型与外部工具和数据源进行标准化交互。TinyClaw 实现了完整的 MCP 客户端。

### 7.2 三种传输方式

| 传输方式 | 实现类 | 适用场景 |
|----------|--------|----------|
| SSE | `SSEMCPClient` | 远程 HTTP 服务器（Server-Sent Events） |
| Stdio | `StdioMCPClient` | 本地进程通信（标准输入/输出） |
| Streamable HTTP | `StreamableHttpMCPClient` | 远程 HTTP 服务器（流式 HTTP） |

### 7.3 工作流程

```text
MCPManager.initialize()
    │
    ▼
遍历 MCPServersConfig
    │
    ▼ (对每个服务器)
createClient(serverConfig)
    │  根据传输类型创建对应的 MCPClient
    │
    ▼
client.connect()
    │
    ▼
sendRequest("initialize", ...)
    │  MCP 协议握手
    │
    ▼
sendNotification("notifications/initialized", ...)
    │
    ▼
sendRequest("tools/list", ...)
    │  获取服务器提供的工具列表
    │
    ▼
对每个工具创建 MCPTool 并注册到 ToolRegistry
    │  LLM 可直接调用这些工具
    │
    ▼
完成初始化
```

### 7.4 自动重连

`MCPTool` 在检测到连接断开时，会自动调用 `MCPManager.reconnect()` 重新建立连接，无需人工干预。

### 7.5 配置示例

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
      "timeout": 30
    },
    "web-search": {
      "endpoint": "https://mcp-server.example.com/sse",
      "apiKey": "your-api-key",
      "timeout": 30
    }
  }
}
```

---

## 八、安全体系

### 8.1 多层安全架构

```text
┌─────────────────────────────────────────┐
│ 第一层：通道白名单（身份验证）            │
│   每个通道配置 allowFrom 白名单           │
│   只有授权用户可以与 Agent 交互           │
├─────────────────────────────────────────┤
│ 第二层：Web 安全中间件                    │
│   SecurityMiddleware 处理认证和 CORS      │
├─────────────────────────────────────────┤
│ 第三层：工作空间沙箱                      │
│   SecurityGuard.checkFilePath()          │
│   所有文件操作限制在 workspace 目录内      │
│   路径规范化防止遍历攻击                   │
├─────────────────────────────────────────┤
│ 第四层：命令黑名单                        │
│   SecurityGuard.checkCommand()           │
│   阻止 rm -rf、mkfs、dd 等危险命令        │
│   支持自定义黑名单扩展                     │
├─────────────────────────────────────────┤
│ 第五层：结构化日志审计                     │
│   TinyClawLogger 记录所有关键操作          │
│   工具调用时长、结果长度等指标              │
└─────────────────────────────────────────┘
```

### 8.2 SecurityGuard 核心能力

| 能力 | 说明 |
|------|------|
| 路径沙箱 | 解析为绝对路径后检查是否在 workspace 内 |
| 命令过滤 | 内置危险命令黑名单 + 自定义扩展 |
| 路径规范化 | `normalize()` 防止 `../` 遍历 |
| 可配置性 | 支持关闭沙箱限制（开发环境） |

---

## 九、Web 控制台

### 9.1 架构

```text
WebConsoleServer（轻量 HTTP 服务器）
    │
    ├── SecurityMiddleware（认证 + CORS）
    │
    └── 16 个 REST API Handler
            │
            ├── 对话相关
            │   ├── ChatHandler      → 对话交互（支持 SSE 流式）
            │   ├── SessionsHandler  → 会话管理
            │   └── FeedbackHandler  → 用户反馈
            │
            ├── 配置管理
            │   ├── ConfigHandler    → 全局配置
            │   ├── ModelsHandler    → 模型列表与切换
            │   └── ProvidersHandler → Provider 管理
            │
            ├── 功能管理
            │   ├── ChannelsHandler  → 通道状态
            │   ├── SkillsHandler    → 技能管理
            │   ├── CronHandler      → 定时任务
            │   └── MCPHandler       → MCP 服务器管理
            │
            ├── 文件与工作空间
            │   ├── FilesHandler     → 文件浏览
            │   ├── UploadHandler    → 文件上传
            │   └── WorkspaceHandler → 工作空间管理
            │
            ├── 监控与统计
            │   └── TokenStatsHandler → Token 用量统计
            │
            └── 基础设施
                ├── AuthHandler      → 认证
                └── StaticHandler    → 静态资源
```

### 9.2 关键特性

- **流式对话**：ChatHandler 支持 SSE（Server-Sent Events），实时输出 Agent 回复
- **模型热切换**：通过 ModelsHandler 在运行时切换模型，无需重启
- **反馈闭环**：FeedbackHandler 收集用户反馈，驱动进化引擎
- **Token 监控**：TokenStatsHandler 提供 Token 用量统计和趋势分析

---

## 十、与同类框架对比

### 10.1 全景对比

| 维度 | **TinyClaw** | LangChain | Semantic Kernel | AutoGen |
|------|-------------|-----------|-----------------|---------|
| **语言** | **Java 17** | Python | C#/.NET | Python |
| **定位** | **生产运行时** | 通用 Agent 库 | 企业 AI 编排 | 多 Agent 框架 |
| **部署** | **单 JAR** | pip 安装 | NuGet + .NET | pip 安装 |
| **多通道** | **7+（内置）** | 需手动集成 | 需手动集成 | 无 |
| **多 Agent** | **7 种模式** | 需手动编排 | 部分支持 | 对话式 |
| **自我进化** | **3 种策略** | 无 | 无 | 无 |
| **MCP 支持** | **3 种传输** | 社区插件 | 无 | 无 |
| **安全机制** | **路径沙箱 + 命令过滤** | 无内置 | 部分限制 | 无 |
| **Web 控制台** | **16 个 API** | 无 | 无 | 无 |
| **技能系统** | **Markdown + 语义搜索** | 无 | 无 | 无 |
| **企业友好度** | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ |

### 10.2 定位差异

```text
LangChain      ≈ Spring Framework（需要你写代码组装）
Semantic Kernel ≈ .NET 企业框架（需要 .NET 生态）
AutoGen        ≈ 多 Agent 对话库（专注对话式协作）
TinyClaw       ≈ Tomcat（配置后直接运行的完整运行时）
```

### 10.3 TinyClaw 的独特优势

- **唯一同时支持 7 种协同模式 + 工作流引擎**的轻量框架
- **唯一内置 3 种 Prompt 自动优化策略**的 Agent 框架
- **唯一同时支持 7+ IM 通道 + MCP 协议 + Web 控制台**的单 JAR 部署方案
- **唯一内置多层安全防护**（沙箱 + 黑名单 + 白名单 + 审计）的个人 AI 助手

---

## 十一、Demo 演示指南

### Demo 0：一键演示模式（推荐首选）

```bash
# 前置：完成构建、onboard 和 API Key 配置
java -jar target/tinyclaw-0.1.0.jar demo agent-basic
```

自动跑完一轮 CLI 对话流程。对照日志输出，可以讲解从 `TinyClaw.main` → `DemoCommand` → `AgentLoop.processDirect` 的完整调用链。

### Demo 1：本地 CLI 助手

```bash
java -jar target/tinyclaw-0.1.0.jar agent
```

随便问一个问题，一边看终端输出，一边对照 `TinyClaw.java` → `AgentCommand` → `MessageRouter.routeUser` → `LLMExecutor.execute` 的调用链来讲解。

### Demo 2：网关 + 单通道机器人

```bash
# 在 config.json 中启用一个通道（如 Telegram），填好 token 和 allowFrom
java -jar target/tinyclaw-0.1.0.jar gateway
```

从 IM 客户端发消息，观察 MessageBus 进出站日志，演示"消息通道 → 消息总线 → MessageRouter → LLMExecutor → 通道"的完整闭环。

### Demo 3：Web 控制台

```bash
# 在 gateway 模式下，访问默认端口
open http://localhost:18791
```

可以实时查看 Agent 状态、会话列表、切换模型、管理技能、查看 Token 用量等。

### Demo 4：定时任务播报

```bash
tinyclaw cron add --name "demo" --message "这是一条演示任务" --every 30
```

保持 gateway 运行，等待定时任务触发并在通道中看到播报消息。

### Demo 5：MCP 集成

```bash
# 在 config.json 中配置 MCP 服务器
java -jar target/tinyclaw-0.1.0.jar mcp list
```

查看已连接的 MCP 服务器和可用工具列表。

### Demo 6：多 Agent 协同

在对话中让 Agent 使用 `collaborate` 工具：

```text
用户: 请用辩论模式分析"微服务 vs 单体架构"的优劣
Agent: (自动调用 collaborate 工具，启动 debate 模式)
       → 创建正反方 Agent
       → 多轮辩论
       → 汇总结论
```

---

## 十二、未来展望

### 短期规划

- **技能签名验证**：防止恶意技能加载
- **分布式部署**：支持多实例协同
- **更多 MCP 服务器**：扩展外部工具生态

### 中期规划

- **Agent 市场**：共享和发现 Agent 配置
- **可视化工作流编辑器**：拖拽式工作流设计
- **A/B 测试框架**：自动对比 Prompt 变体效果

### 长期愿景

- **自主 Agent 网络**：多个 TinyClaw 实例组成协作网络
- **领域专家 Agent**：预训练的垂直领域 Agent 模板
- **边缘智能**：在 IoT 设备上运行的超轻量 Agent

---

## 附录：技术栈速查

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 构建 | Maven | 3.x |
| HTTP 客户端 | OkHttp | 4.12 |
| JSON | Jackson | 2.17 |
| 日志 | SLF4J + Logback | 2.0 + 1.5 |
| 命令行 | JLine | 3.25 |
| Cron | cron-utils | 9.2 |
| 环境变量 | dotenv-java | 3.0 |
| 测试 | JUnit 5 + Mockito | 5.10 + 5.10 |

---

> 🦞 TinyClaw — 让 AI Agent 从原型走向生产，从单体走向协同，从静态走向进化。
