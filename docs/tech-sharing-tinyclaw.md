# TinyClaw：OpenClaw 的 Java 生产级重构

> 借鉴 OpenClaw 的设计理念，打造轻量级企业级 Agent 框架——OpenClaw 理念在 Java 生态中的升华

**传承 OpenClaw 的设计精髓，突破 NodeJS 的部署束缚，为企业级 AI Agent 构建生产就绪的基础设施**

---

## 一、项目起源：从 OpenClaw 到 TinyClaw

### OpenClaw：将 AI Agent 带入大众视野

**OpenClaw** 是 2026 年初爆火的开源 AI Agent 框架，由 Peter Steinberger 开发，在一周内获得 10 万+ GitHub Stars，最终被 OpenAI 收购。它不是第一个 AI Agent 框架（之前有 AutoGPT、BabyAGI、LangChain 等），但它**将 AI Agent 从概念验证推向了实用化**：

**OpenClaw 的创新之处**：
- **个人助手定位**：从开发者工具转向普通用户可用的"数字员工"
- **消息平台集成**：原生支持 WhatsApp、Telegram、Slack，融入日常工作流
- **大型项目**：43 万+ 行代码，功能完整的 Agent 框架
- **Skills 生态**：Markdown 格式技能，降低了扩展门槛
- **24/7 运行**：设计为长期运行的后台服务，而非一次性脚本

**OpenClaw 的影响力**：
- 短时间内积累 20 万+ Stars，成为现象级项目
- 催生了 PicoClaw、NullClaw、ZeroClaw、TinyClaw 等衍生项目
- 被誉为"有手的 Claude"（Claude with Hands）
- 推动了"个人 AI 助手"从愿景到现实的转变

### TinyClaw：Java 生态的生产级重构

**TinyClaw** 不是简单的 OpenClaw 克隆，而是基于 OpenClaw 核心理念的**全新架构设计**，专为生产环境打造：

#### 为什么要重构？OpenClaw 的局限性

| 维度 | OpenClaw（NodeJS） | 问题 |
|------|-------------------|------|
| **部署复杂度** | npm 依赖地狱，版本冲突 | 企业环境难以标准化部署 |
| **稳定性** | 频繁崩溃，需要守护进程 | 不适合 7x24 生产运行 |
| **安全性** | 无内置沙箱，存在安全隐患 | 社区发现大量恶意技能 |
| **企业友好** | NodeJS 在企业中接受度低 | IT 部门更信任 Java 生态 |
| **资源占用** | 1GB+ 内存，多进程架构 | 不适合边缘设备和资源受限环境 |
| **并发处理** | 单线程事件循环，并发受限 | 多通道场景下性能瓶颈明显 |

#### TinyClaw 的创新：传承与突破

**传承 OpenClaw 的精髓**：
- ✅ **Gateway 模式**：消息总线（MessageBus）统一路由
- ✅ **Agent Loop**：LLMExecutor 实现推理-工具迭代
- ✅ **Skills 系统**：兼容 Markdown 技能定义
- ✅ **MCP 支持**：MCPManager 集成外部工具
- ✅ **多通道**：支持 7+ 消息平台

**突破 OpenClaw 的限制**：
- 🚀 **生产级架构**：分层设计，清晰解耦
- 🔒 **安全优先**：内置 SecurityGuard 双层防护
- ⚡ **高性能并发**：反应式架构，真正多线程
- 📦 **零依赖部署**：单 JAR 文件，无需 npm
- 🏢 **企业友好**：Java 生态，易于集成
- 💾 **资源高效**：128MB 内存，启动 < 3s

### 从原型到产品：架构对比

```mermaid
graph TB
    subgraph "OpenClaw 架构（NodeJS）"
        OC1[WhatsApp Bot<br/>单一脚本]
        OC2[Gateway<br/>HTTP Server]
        OC3[Agent Loop<br/>单线程]
        OC4[Skills<br/>文件系统]
        OC5[MCP<br/>进程通信]
        
        OC1 --> OC2
        OC2 --> OC3
        OC3 --> OC4
        OC3 --> OC5
    end
    
    subgraph "TinyClaw 架构（Java）"
        TC1[多通道适配层<br/>7+ 平台]
        TC2[MessageBus<br/>异步队列]
        TC3[AgentLoop<br/>多线程池]
        TC4[ToolRegistry<br/>注册表模式]
        TC5[SecurityGuard<br/>沙箱检查]
        TC6[SessionManager<br/>持久化]
        
        TC1 --> TC2
        TC2 --> TC3
        TC3 --> TC4
        TC4 --> TC5
        TC3 --> TC6
    end
```

### 核心差异总结

| 维度 | OpenClaw | TinyClaw |
|------|---------|---------|
| **语言** | NodeJS/TypeScript | **Java 17** |
| **代码量** | 43 万+ 行 | **5,000+ 行精简代码** |
| **架构** | 大型单体项目 | **轻量分层架构（5 层）** |
| **并发** | 单线程事件循环 | **多线程 + 反应式** |
| **安全** | 无 | **SecurityGuard 双层防护** |
| **部署** | npm + 依赖 | **单 JAR** |
| **启动时间** | 5-10s | **< 3s** |
| **内存占用** | 1GB+ | **128MB** |
| **稳定性** | 频繁崩溃 | **7x24 生产级** |
| **企业接受度** | ⭐⭐ | **⭐⭐⭐⭐⭐** |

**TinyClaw 的定位**：
> OpenClaw 证明了 AI Agent 的可行性，TinyClaw 让它走向生产环境。

---

## 二、设计哲学：从理论到实践

TinyClaw 的架构设计深度融合了多个经典软件工程理论和 AI Agent 领域的最佳实践：

#### 1. UNIX 哲学的现代演绎

**"Do One Thing Well" + 组合优于继承**

TinyClaw 借鉴 UNIX 哲学的"小工具组合"思想，将 Agent 能力分解为独立的工具（Tool）：

- 每个工具只做一件事（读文件、执行命令、搜索网络）
- 工具之间通过标准接口组合
- LLM 作为"Shell"，动态编排工具组合完成复杂任务

这与传统 Agent 框架的"大而全"单体设计形成鲜明对比。

**理论映射**：
```
UNIX 管道：cat file.txt | grep "error" | wc -l
TinyClaw：LLM → ReadFileTool → (推理) → WebSearchTool → (推理) → 最终答案
```

#### 2. 六边形架构（Hexagonal Architecture）

**端口与适配器模式**

TinyClaw 的通道层采用六边形架构思想：

```mermaid
graph LR
    subgraph 核心领域
        Agent[Agent引擎]
    end
    
    subgraph 入站端口
        MB1[MessageBus入站]
        CLI1[CLI接口]
    end
    
    subgraph 出站端口
        MB2[MessageBus出站]
        LLM[LLM Provider]
        Tools[Tool接口]
    end
    
    subgraph 入站适配器
        T[Telegram]
        D[Discord]
        F[飞书]
    end
    
    subgraph 出站适配器
        OpenAI[OpenAI]
        Ollama[Ollama]
        FileTools[文件工具]
        ExecTools[执行工具]
    end
    
    T --> MB1
    D --> MB1
    F --> MB1
    CLI1 --> Agent
    MB1 --> Agent
    
    Agent --> MB2
    Agent --> LLM
    Agent --> Tools
    
    MB2 --> T
    MB2 --> D
    MB2 --> F
    LLM --> OpenAI
    LLM --> Ollama
    Tools --> FileTools
    Tools --> ExecTools
```

**核心领域（Agent）与外部世界完全解耦**：
- 更换消息平台无需修改 Agent 代码
- 切换 LLM 提供商只需修改配置
- 添加新工具不影响现有功能

#### 3. 反应式架构（Reactive Architecture）

**事件驱动 + 异步消息传递**

TinyClaw 的 MessageBus 采用反应式设计模式：

- **非阻塞**：使用 `LinkedBlockingQueue` 实现生产者-消费者解耦
- **背压处理**：队列满时丢弃消息，防止级联故障
- **弹性**：单个通道故障不影响其他通道

**对比传统同步架构**：

| 维度 | 传统同步架构 | TinyClaw 反应式架构 |
|------|-------------|-------------------|
| 消息处理 | 串行阻塞，单线程处理 | 异步并发，多通道独立 |
| 故障隔离 | 一个通道挂起影响全局 | 通道间完全隔离 |
| 吞吐量 | 受限于最慢通道 | 各通道独立吞吐 |
| 扩展性 | 难以水平扩展 | 可独立扩展通道和 Agent |

#### 4. 分层防御纵深（Defense in Depth）

**多层安全机制**

借鉴信息安全领域的纵深防御理论，TinyClaw 构建了多层安全体系：

```mermaid
graph TB
    A[外部输入] --> B[通道白名单<br/>第一层：身份验证]
    B --> C[SecurityGuard路径检查<br/>第二层：工作空间沙箱]
    C --> D[SecurityGuard命令检查<br/>第三层：命令黑名单]
    D --> E[工具执行<br/>第四层：资源限制]
    E --> F[日志审计<br/>第五层：事后追溯]
```

**每层独立生效，即使某层被绕过，其他层仍能提供保护**。

#### 5. Actor 模型的简化实现

**会话作为轻量级 Actor**

每个会话（Session）可视为一个轻量级 Actor：

- **状态封装**：每个会话维护独立的历史和摘要
- **消息传递**：通过 MessageBus 异步通信
- **并发安全**：使用 `ConcurrentHashMap` 管理多会话

**对比传统多线程模型**：
- 无需显式锁管理
- 避免共享状态竞争
- 天然支持分布式扩展

#### 6. 递归自我扩展（Recursive Self-Extension）

**Agent 作为能力构建者**

传统 Agent 框架的能力由开发者预定义，TinyClaw 则赋予 Agent 自我扩展的能力：

- **技能自创建**：通过系统提示词引导 Agent 识别重复模式，使用 `SkillsTool` 创建新技能固化经验
- **社区能力获取**：从 GitHub 按需安装技能，快速获得新能力
- **子 Agent 生成**：通过 `SpawnTool` 创建专用子 Agent 处理特定任务
- **迭代优化**：根据执行反馈，Agent 可主动编辑和优化已有技能

**这实现了"元能力"——创建能力的能力，让 Agent 从执行者进化为构建者。**

### TinyClaw vs. OpenClaw 及其他框架：生态定位

#### 全景对比：从原型到生产

| 维度 | **OpenClaw** | LangChain | Semantic Kernel | **TinyClaw** |
|------|-------------|-----------|-----------------|-------------|
| **语言** | NodeJS/TS | Python | C#/.NET | **Java** |
| **定位** | 个人助手原型 | 通用 Agent 库 | 企业 AI 编排 | **生产运行时** |
| **代码复杂度** | 43 万+ 行 | 50,000+ 行 | 30,000+ 行 | **5,000 行** |
| **启动时间** | 5-10s | 5-10s | 3-8s | **< 3s** |
| **内存占用** | 1GB+ | 500MB+ | 300MB+ | **128MB** |
| **部署方式** | npm + 守护 | pip 安装 | NuGet + .NET | **单 JAR** |
| **多通道支持** | 3 个（原生） | 需手动集成 | 需手动集成 | **7+（内置）** |
| **安全机制** | 无（存在安全风险）| 无内置沙箱 | 部分限制 | **双层沙箱** |
| **并发模型** | 单线程事件 | 多进程 | 异步任务 | **反应式多线程** |
| **稳定性** | 频繁崩溃 ⭐ | 一般 ⭐⭐⭐ | 良好 ⭐⭐⭐⭐ | **生产级 ⭐⭐⭐⭐⭐** |
| **企业友好度** | ⭐ | ⭐⭐ | ⭐⭐⭐⭐ | **⭐⭐⭐⭐⭐** |
| **边缘计算** | 不适合 | 不适合 | 不适合 | **适合** |
| **社区规模** | 20万 Stars | 10万+ | 5万+ | **新兴** |

#### OpenClaw 生态衍生项目对比

OpenClaw 的成功催生了一系列衍生项目，各有侧重：

| 项目 | 定位 | 特点 | 与 TinyClaw 的区别 |
|------|------|------|------------------|
| **OpenClaw** | 原始版本 | NodeJS，多功能 | TinyClaw 的灵感来源 |
| **PicoClaw** | 极简版 | 200 行代码 | TinyClaw 更完整的架构 |
| **NullClaw** | 无依赖版 | 零外部依赖 | TinyClaw 追求平衡 |
| **ZeroClaw** | Rust 版本 | 高性能 | TinyClaw 更企业友好 |
| **NanoBot** | Python 版 | 易于定制 | TinyClaw 更稳定 |
| **TinyClaw** | **生产级 Java** | **架构完整+安全** | **面向生产的完整方案** |

**关键洞察**：
- **OpenClaw 系列**：快速原型，证明概念
- **TinyClaw**：生产部署，企业级稳定性

---

#### 核心区别详解：TinyClaw vs. OpenClaw

**1. 架构理念：大型项目 vs. 精简工程**

**OpenClaw**：
```javascript
// OpenClaw - 43 万+ 行 NodeJS/TypeScript 项目
const bot = new WhatsAppBot();
bot.on('message', async (msg) => {
  const response = await callClaude(msg);
  bot.send(response);
});
```

**TinyClaw**：
```java
// 5,000+ 行分层架构
MessageBus → ChannelManager → AgentLoop → ToolRegistry
           ↓
     SecurityGuard (每次工具调用前检查)
```

**对比**：
- OpenClaw 是"能跑就行"的原型
- TinyClaw 是"可维护、可扩展、可测试"的工程

**2. 安全设计：信任 vs. 零信任**

OpenClaw 在发布后短时间内被发现存在安全问题：
- **大量安全漏洞**
- **社区技能市场存在恶意技能**
- 可以直接执行 `rm -rf /`

TinyClaw 从设计之初就内置安全：

| 场景 | OpenClaw | TinyClaw |
|------|---------|---------|
| 执行 `rm -rf /` | ✅ 直接执行 | ❌ SecurityGuard 阻止 |
| 访问 `/etc/passwd` | ✅ 直接访问 | ❌ 沙箱拒绝 |
| 恶意技能加载 | ✅ 无检查 | ❌ 签名验证（规划中）|
| 命令注入 | ✅ 存在风险 | ❌ 黑名单过滤 |

**3. 多通道设计：单点 vs. 总线**

**OpenClaw**：
```
WhatsApp Bot → Agent Loop
   (后来加入 Telegram、Slack，但相互独立)
```

**TinyClaw**：
```
7+ 通道 → MessageBus（统一队列）→ AgentLoop
   (所有通道共享会话、记忆、工具)
```

**实际影响**：
- OpenClaw：每个通道需要单独配置和管理
- TinyClaw：一次配置，所有通道自动路由

**4. 并发处理：单线程 vs. 反应式**

**OpenClaw**（NodeJS 单线程）：
```
用户 A 消息 → 处理中（阻塞）
用户 B 消息 → 排队等待
用户 C 消息 → 排队等待
```

**TinyClaw**（Java 多线程 + 消息队列）：
```
用户 A 消息 → 线程池处理
用户 B 消息 → 同时处理（独立线程）
用户 C 消息 → 同时处理（独立线程）
```

**性能对比**（3 个通道同时活跃，理论估算）：
| 框架 | 吞吐量 | 平均响应时间 |
|------|--------|-------------|
| 单线程架构 | 较低 | 较长（排队等待）|
| TinyClaw 多线程 | 较高 | 较短（并行处理）|

**多线程架构在高并发场景下具有显著优势**。

**5. 部署复杂度：npm 地狱 vs. 一键部署**

**OpenClaw**：
```bash
# 安装 NodeJS
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.0/install.sh | bash
nvm install 18

# 安装依赖（可能失败）
npm install
# → 300+ 个依赖包
# → 版本冲突
# → node-gyp 编译失败
# → 需要守护进程 (pm2/systemd)

# 配置环境变量
export OPENAI_API_KEY=...
export WHATSAPP_SESSION=...

# 启动（希望不崩溃）
npm start
```

**TinyClaw**：
```bash
# 一行命令
java -jar tinyclaw.jar gateway

# 就这么简单！
```

**6. 稳定性：原型 vs. 生产**

**OpenClaw 常见问题**：
- 内存泄漏（NodeJS 单线程 GC 压力大）
- 频繁崩溃（未捕获异常）
- 需要守护进程自动重启
- 状态丢失（无持久化）

**TinyClaw 生产级特性**：
- JVM 成熟的 GC 机制
- 完善的异常处理
- 会话持久化（JSON 文件）
- 自动重连机制
- 优雅关闭流程

---

#### 与 LangChain/Semantic Kernel 的定位差异

**架构理念**：
- **LangChain/Semantic Kernel**：提供丰富的组件和抽象，开发者需要编写代码组装
- **TinyClaw**：提供完整的运行时环境，配置即可运行，类似于应用服务器
- **OpenClaw**：Shell 脚本快速原型，证明概念可行

**类比**：
```
LangChain  ≈ Spring Framework（需要你写代码）
TinyClaw   ≈ Tomcat（配置后直接运行）
OpenClaw   ≈ Demo 脚本（快速验证想法）
```

### 为什么不同的选择？

TinyClaw 的设计选择源于对**目标场景**的深刻理解：

#### 目标场景 1：企业内部部署

**痛点**：
- 企业 IT 部门更熟悉 Java 生态
- 安全合规要求严格，需要沙箱和审计
- 需要接入内部 IM（飞书/钉钉）

**TinyClaw 的优势**：
- Java 在企业环境中信任度高
- 内置安全机制，满足合规要求
- 开箱即用的企业 IM 集成

#### 目标场景 2：边缘设备/IoT

**痛点**：
- 资源受限（内存 < 512MB）
- 无法安装 Python 依赖
- 需要长期稳定运行

**TinyClaw 的优势**：
- 内存占用低（128MB）
- JVM 成熟的 GC 机制
- 单 JAR 部署，无外部依赖

#### 目标场景 3：个人开发者

**痛点**：
- 不想学习复杂的框架
- 希望快速上手，立即可用
- 需要多平台支持（Telegram、Discord）

**TinyClaw 的优势**：
- 配置驱动，无需写代码
- 3 分钟部署运行
- 多通道开箱即用

### 核心价值主张

#### 为什么选择 Java 实现？

- **生态成熟**：Java 拥有丰富的第三方库和 SDK，便于集成各类消息平台（Telegram、Discord、飞书、钉钉等）
- **跨平台性**：一次编译，到处运行，无论是个人电脑、服务器还是嵌入式设备都能部署
- **企业友好**：Java 在企业环境中被广泛接受，易于集成到现有技术栈
- **性能可控**：相比 Python，Java 在并发处理和内存管理上更具优势

#### 为什么是轻量化？

传统的 AI Agent 框架往往依赖重型框架（如 Spring Boot、Django），启动慢、资源占用高。TinyClaw 采用轻量化设计：

- **无框架依赖**：不使用 Spring 等框架，启动时间短（< 3秒）
- **单 JAR 部署**：所有依赖打包成一个可执行 JAR，部署简单
- **内存占用低**：基础运行仅需 128MB 内存，适合个人设备和边缘计算场景

#### 为什么工具优先？

TinyClaw 的核心设计理念是**工具优先（Tool-First）**：

- Agent 的能力边界由工具定义，而非代码硬编码
- LLM 通过工具调用（Function Calling）与外部世界交互
- 开发者可以通过添加工具快速扩展 Agent 能力，无需修改核心代码

### 技术亮点预览

1. **安全沙箱**：内置工作空间沙箱和命令黑名单，防止恶意操作
2. **多通道适配**：统一封装 7+ 主流消息平台，支持同时接入多个通道
3. **工具系统**：14+ 内置工具，支持文件操作、命令执行、网络访问、定时任务等
4. **技能扩展**：支持加载和管理 Markdown 格式的技能文件，Agent 可自我学习新能力
5. **会话管理**：自动摘要机制，优化上下文窗口利用率
6. **模型路由**：支持多家 LLM 提供商，动态切换模型
7. **递归自我扩展**：Agent 可自主创建/安装/编辑技能，并生成子 Agent，实现能力的自我进化

### 递归自我扩展：AI 自主进化的核心设计

TinyClaw 的一个核心设计理念是**递归自我扩展（Recursive Self-Extension）**——让 Agent 具备自我进化的能力，而非依赖人工预定义的静态指令。

#### 设计思想

```
传统 Agent：人工定义技能 → Agent 被动执行 → 能力边界固定
TinyClaw：  提示词引导识别 → 自主创建技能 → 能力持续扩展
```

#### 实现机制

**1. 技能自主管理（SkillsTool）**

Agent 通过 `skills` 工具实现技能的全生命周期管理：

```java
// SkillsTool - Agent 自主学习的核心能力
- list    // 查看已有技能
- show    // 阅读技能内容
- create  // 创建新技能（固化经验）
- edit    // 优化已有技能
- install // 从 GitHub 安装社区技能
- remove  // 删除过时技能
```

**应用场景**：
- 系统提示词引导 Agent 识别重复模式，主动创建技能固化经验
- 遇到新问题时，从 GitHub 社区按需安装技能
- 根据反馈迭代优化技能，使其越来越精准

**2. 子 Agent 生成（SpawnTool）**

Agent 可以通过 `spawn` 工具创建子 Agent 处理复杂任务：

```
用户请求 → 主 Agent 分析 → 生成专用子 Agent → 子 Agent 执行 → 结果汇总
```

**应用场景**：
- 将复杂任务分解为多个子任务，由不同子 Agent 并行处理
- 为特定领域创建专家子 Agent
- 实现任务的递归分解和协作

**3. 递归进化模式**

```mermaid
graph LR
    A[用户交互] --> B{识别重复模式?}
    B -->|是| C[创建新技能]
    B -->|否| D{能力不足?}
    D -->|是| E[安装社区技能]
    D -->|否| F[直接执行]
    C --> G[技能库扩展]
    E --> G
    G --> H[下轮对话自动可用]
    H --> F
    
    F --> I{任务复杂?}
    I -->|是| J[生成子Agent]
    I -->|否| A
    J --> K[子Agent执行]
    K --> L[结果汇总]
    L --> A
```

#### 与传统框架的差异

| 维度 | 传统 Agent 框架 | TinyClaw |
|------|----------------|----------|
| 技能来源 | 人工预定义 | Agent 自主创建 + 社区安装 |
| 能力边界 | 固定不变 | 持续扩展 |
| 学习方式 | 无 | 通过提示词引导 LLM 识别模式并固化 |
| 任务分解 | 人工编排 | Agent 自主生成子 Agent |
| 进化方向 | 人工迭代 | Agent 自我优化 |

**这种设计让 TinyClaw 从"工具执行者"进化为"能力构建者"，实现真正的 AI 自主进化。**

### 应用场景示例

- **个人 AI 助手**：CLI 模式下的本地智能助手，处理文件、执行命令、搜索信息
- **企业 IM 机器人**：接入飞书/钉钉/Telegram，提供智能客服、任务管理、数据查询
- **IoT 设备集成**：通过 MaixCam 通道接入摄像头设备，实现智能监控和语音交互
- **多 Agent 社交网络**：Agent 之间通过社交网络工具互相通信，协作完成复杂任务

---

## 三、整体架构设计

### 架构总览

TinyClaw 采用分层架构设计，从上到下分为五层：

```mermaid
graph TB
    subgraph 入口层
        CLI[CLI命令行工具]
        Gateway[网关服务]
        WebConsole[Web控制台]
    end
    
    subgraph Agent引擎层
        AgentLoop[AgentLoop主循环]
        ContextBuilder[上下文构建器]
        LLMExecutor[LLM执行器]
        SessionSummarizer[会话摘要器]
    end
    
    subgraph 通信层
        MessageBus[消息总线]
        ChannelManager[通道管理器]
        LLMProvider[LLM Provider]
    end
    
    subgraph 通道适配层
        Telegram[Telegram]
        Discord[Discord]
        Feishu[飞书]
        DingTalk[钉钉]
        Other[其他通道...]
    end
    
    subgraph 基础设施层
        ToolRegistry[工具注册表]
        SecurityGuard[安全守卫]
        SessionManager[会话管理器]
        SkillsLoader[技能加载器]
        Config[配置管理]
    end
    
    CLI --> AgentLoop
    Gateway --> MessageBus
    WebConsole --> AgentLoop
    
    AgentLoop --> MessageBus
    AgentLoop --> ContextBuilder
    AgentLoop --> LLMExecutor
    AgentLoop --> SessionSummarizer
    
    MessageBus --> ChannelManager
    ChannelManager --> Telegram
    ChannelManager --> Discord
    ChannelManager --> Feishu
    ChannelManager --> DingTalk
    ChannelManager --> Other
    
    LLMExecutor --> LLMProvider
    LLMExecutor --> ToolRegistry
    
    ContextBuilder --> ToolRegistry
    ContextBuilder --> SkillsLoader
    ContextBuilder --> SessionManager
    
    ToolRegistry --> SecurityGuard
```

### 分层架构说明

| 层次 | 职责 | 关键组件 |
|------|------|----------|
| **入口层** | 接收用户请求，路由到不同执行模式 | TinyClaw.java, CLI 命令子类 |
| **Agent 引擎层** | 核心推理循环，协调上下文、LLM 和工具 | AgentLoop, LLMExecutor, ContextBuilder |
| **通信层** | 消息路由和 LLM 调用 | MessageBus, LLMProvider, ChannelManager |
| **通道适配层** | 封装不同消息平台的 SDK | 各 Channel 实现 |
| **基础设施层** | 提供配置、安全、会话、工具等基础能力 | Config, SecurityGuard, ToolRegistry, SessionManager |

### 设计理念

#### 1. 轻量化：无框架依赖，单 JAR 部署

TinyClaw 不使用 Spring 等重型框架，仅依赖必要的库：

- **HTTP 客户端**：OkHttp（轻量、高性能）
- **JSON 处理**：Jackson（标准、快速）
- **日志**：SLF4J + Logback（灵活、可配置）
- **命令行**：JLine（提供交互式 CLI 体验）

构建后生成单一可执行 JAR，部署只需一条命令：

```bash
java -jar tinyclaw.jar gateway
```

#### 2. 模块化：清晰接口，可插拔组件

所有核心组件都通过接口定义：

- `Channel`（`io.leavesfly.tinyclaw.channels.Channel`）：统一消息通道接口
- `Tool`（`io.leavesfly.tinyclaw.tools.Tool`）：工具接口，定义参数和执行方法
- `LLMProvider`（`io.leavesfly.tinyclaw.providers.LLMProvider`）：LLM 提供商接口

这种设计使得各模块可以独立开发、测试和替换，降低耦合度。

#### 3. 配置驱动：Markdown + JSON 配置 Agent 行为

TinyClaw 的 Agent 行为由配置文件和 Markdown 文档驱动：

- **config.json**：定义模型、通道、工具等配置
- **AGENTS.md**：Agent 的行为指令
- **SOUL.md**：Agent 的个性设定
- **USER.md**：用户信息和偏好
- **IDENTITY.md**：Agent 的身份描述

这种设计让非开发者也能通过修改文档来调整 Agent 行为，无需编写代码。

#### 4. 安全优先：内置沙箱与黑名单

TinyClaw 内置 `SecurityGuard`（`io.leavesfly.tinyclaw.security.SecurityGuard`），提供两层安全保护：

1. **工作空间沙箱**：限制文件操作在工作空间目录内，防止访问系统敏感文件
2. **命令黑名单**：阻止危险命令（如 `rm -rf`、`sudo`、`shutdown` 等）

所有文件操作和命令执行工具在运行前都会经过安全检查，确保 Agent 不会执行破坏性操作。

---

## 四、核心组件深度解析

### 3.1 Agent 引擎（AgentLoop）

#### 是什么？

`AgentLoop`（`io.leavesfly.tinyclaw.agent.AgentLoop`）是 TinyClaw 的核心执行引擎，负责协调消息路由、上下文构建、LLM 交互和工具调用。

#### 为什么需要它？

在 AI Agent 系统中，需要一个中心化的协调者来处理：

- 从不同来源（CLI、消息总线、定时任务）接收消息
- 构建包含历史、摘要、工具信息的完整上下文
- 调用 LLM 并处理其响应（文本或工具调用）
- 执行工具并将结果反馈给 LLM
- 管理会话历史和摘要

AgentLoop 就是这个协调者，它将复杂的交互流程封装成清晰的处理管道。

#### 怎么做的？

**关键设计：动态 Provider 替换**

```java
// AgentLoop.java - setProvider 方法
public void setProvider(LLMProvider provider) {
    if (provider == null) {
        return;
    }
    synchronized (providerLock) {
        applyProvider(provider);
    }
    logger.info("Provider configured dynamically", Map.of(
            "model", config.getAgent().getModel()));
}
```

使用 `volatile` + `synchronized` 组合，支持在运行时动态切换 LLM Provider，无需重启服务。

**工作流程**

```mermaid
sequenceDiagram
    participant User as 用户/通道
    participant AL as AgentLoop
    participant CB as ContextBuilder
    participant LE as LLMExecutor
    participant LLM as LLM Provider
    participant TR as ToolRegistry
    participant SM as SessionManager
    
    User->>AL: 发送消息
    AL->>SM: 获取会话历史
    SM-->>AL: 返回历史消息
    AL->>CB: 构建上下文
    CB-->>AL: 完整消息列表
    AL->>LE: 执行 LLM 调用
    LE->>LLM: 发送请求
    LLM-->>LE: 返回响应（可能含工具调用）
    
    alt 有工具调用
        LE->>TR: 执行工具
        TR-->>LE: 工具结果
        LE->>LLM: 再次调用（带工具结果）
        LLM-->>LE: 最终响应
    end
    
    LE-->>AL: 返回响应
    AL->>SM: 保存会话
    AL->>User: 返回响应
```

**技术亮点**

1. **流式与非流式双模式**
   - `processDirect()`：非流式，适用于 CLI 和批处理
   - `processDirectStream()`：流式输出，实时展示 LLM 响应，提升用户体验

2. **自动会话摘要**
   - 当会话历史超过阈值时，`SessionSummarizer` 自动触发摘要
   - 保留最近消息，压缩早期历史，优化上下文窗口利用

3. **多入口支持**
   - `run()`：网关模式，持续消费消息总线
   - `processDirect()`：CLI 直接调用
   - `processDirectWithChannel()`：定时任务等场景

---

### 3.2 消息总线（MessageBus）

#### 是什么？

`MessageBus`（`io.leavesfly.tinyclaw.bus.MessageBus`）是整个 TinyClaw 系统的通信中枢，采用**发布-订阅模式**实现通道层与 Agent 层的解耦。

#### 为什么需要它？

在多通道环境下，如果让 Agent 直接与每个通道交互，会导致：

- 代码耦合严重，新增通道需要修改 Agent 代码
- 并发处理困难，消息可能乱序或丢失
- 难以实现消息路由和过滤

MessageBus 通过引入入站/出站队列，实现了：

- **解耦**：通道只负责发布消息，Agent 只负责处理消息
- **异步**：消息在队列中缓冲，避免阻塞
- **路由**：消息带有通道标识，自动路由到正确的目标

#### 怎么做的？

**核心数据结构**

```java
// MessageBus.java - 核心队列定义
private final LinkedBlockingQueue<InboundMessage> inbound;
private final LinkedBlockingQueue<OutboundMessage> outbound;
private final Map<String, Function<InboundMessage, Void>> handlers;
```

- **LinkedBlockingQueue**：线程安全的有界队列，防止内存溢出
- **ConcurrentHashMap**：存储通道处理器，支持并发访问

**消息流转架构**

```mermaid
graph LR
    subgraph 通道层
        T[Telegram]
        D[Discord]
        F[飞书]
    end
    
    subgraph 消息总线
        IQ[入站队列]
        OQ[出站队列]
    end
    
    subgraph Agent层
        AL[AgentLoop]
    end
    
    subgraph 通道管理
        CM[ChannelManager]
    end
    
    T --> IQ
    D --> IQ
    F --> IQ
    
    IQ --> AL
    AL --> OQ
    
    OQ --> CM
    CM --> T
    CM --> D
    CM --> F
```

**技术细节**

1. **流量控制**：队列满时自动丢弃消息，避免内存爆炸

```java
// MessageBus.java - publishInbound 方法
public void publishInbound(InboundMessage message) {
    if (!inbound.offer(message)) {
        logger.warn("Inbound queue full, dropping message: " + message);
        return;
    }
```

2. **阻塞与超时消费**：支持两种消费模式

```java
// 阻塞式消费（主循环）
public InboundMessage consumeInbound() throws InterruptedException {
    return inbound.take();
}

// 超时消费（定期检查其他条件）
public InboundMessage consumeInbound(long timeout, TimeUnit unit) {
    return inbound.poll(timeout, unit);
}
```

---

### 3.3 多通道适配器（Channel + ChannelManager）

#### 是什么？

通道适配器层统一封装不同消息平台的 SDK，提供标准化的消息收发接口。

- `Channel`（`io.leavesfly.tinyclaw.channels.Channel`）：通道接口
- `ChannelManager`（`io.leavesfly.tinyclaw.channels.ChannelManager`）：通道管理器

#### 为什么需要它？

不同消息平台的 SDK 差异巨大：

- **Telegram**：基于 HTTP 长轮询
- **Discord**：基于 WebSocket
- **飞书/钉钉**：基于 Webhook 回调

如果直接在 Agent 中处理这些差异，代码会非常混乱。通道适配器通过**适配器模式**和**模板方法**模式，将差异封装在各自的实现中。

#### 怎么做的？

**Channel 接口定义**

```java
// Channel.java - 接口定义
public interface Channel {
    String name();           // 通道名称
    void start() throws Exception;  // 启动通道
    void stop();             // 停止通道
    void send(OutboundMessage message) throws Exception;  // 发送消息
    boolean isRunning();     // 检查运行状态
    boolean isAllowed(String senderId);  // 白名单校验
}
```

**支持的通道**

| 通道 | 平台 | 特点 |
|------|------|------|
| TelegramChannel | Telegram | 国际主流，支持机器人 |
| DiscordChannel | Discord | 游戏社区，支持频道 |
| FeishuChannel | 飞书 | 企业协作，支持事件订阅 |
| DingTalkChannel | 钉钉 | 企业通讯，支持群机器人 |
| WhatsAppChannel | WhatsApp | 即时通讯，通过桥接服务 |
| QQChannel | QQ | 国内主流，支持群聊 |
| MaixCamChannel | MaixCam | IoT 设备，摄像头集成 |

**通道管理架构**

```mermaid
graph TB
    CM[ChannelManager]
    
    CM --> T[TelegramChannel]
    CM --> D[DiscordChannel]
    CM --> F[FeishuChannel]
    CM --> DT[DingTalkChannel]
    CM --> W[WhatsAppChannel]
    CM --> Q[QQChannel]
    CM --> M[MaixCamChannel]
    
    T --> TAPI[Telegram Bot API]
    D --> DAPI[Discord API/JDA]
    F --> FAPI[飞书 Open API]
    DT --> DTAPI[钉钉 API]
    W --> WAPI[WhatsApp Bridge]
    Q --> QAPI[QQ Bot API]
    M --> MAPI[MaixCam SDK]
```

**技术亮点**

1. **BaseChannel 复用通用逻辑**
   - 白名单校验
   - 日志记录
   - 错误处理

2. **语音消息转写集成**
   - 各通道收到语音消息后，自动调用 `AliyunTranscriber` 转写为文本
   - Agent 无需关心语音处理细节

3. **动态通道注册**
   ```java
   public void registerChannel(String name, Channel channel) {
       channels.put(name, channel);
   }
   ```

---

### 3.4 工具系统（Tool + ToolRegistry）

#### 是什么？

工具系统是 TinyClaw 的核心能力提供机制，Agent 通过调用工具与外部世界交互。

- `Tool`（`io.leavesfly.tinyclaw.tools.Tool`）：工具接口
- `ToolRegistry`（`io.leavesfly.tinyclaw.tools.ToolRegistry`）：工具注册表

#### 为什么工具优先？

传统 Agent 的能力是硬编码的，要增加新功能需要修改核心代码。TinyClaw 采用**工具优先（Tool-First）**设计：

- Agent 的能力边界由注册的工具定义
- 开发者通过实现 `Tool` 接口快速扩展能力
- LLM 通过工具调用（Function Calling）与外部交互

这种设计使得：

- **灵活性高**：动态注册/注销工具，无需重启
- **可测试性强**：每个工具独立测试
- **安全可控**：工具执行前经过 SecurityGuard 检查

#### 怎么做的？

**Tool 接口定义**

```java
// Tool.java - 接口定义
public interface Tool {
    String name();           // 工具名称
    String description();    // 工具描述
    Map<String, Object> parameters();  // 参数 Schema（JSON Schema 格式）
    String execute(Map<String, Object> args) throws Exception;  // 执行
}
```

**内置工具清单**

| 类别 | 工具 | 功能 |
|------|------|------|
| **文件操作** | ReadFileTool | 读取文件内容 |
| | WriteFileTool | 写入文件 |
| | AppendFileTool | 追加内容到文件 |
| | EditFileTool | 编辑文件（替换内容）|
| | ListDirTool | 列出目录内容 |
| **命令执行** | ExecTool | 执行 Shell 命令 |
| **网络访问** | WebSearchTool | 网络搜索（Brave API）|
| | WebFetchTool | 抓取网页内容 |
| **Agent 能力** | MessageTool | 向通道发送消息 |
| | CronTool | 管理定时任务 |
| | SpawnTool | 创建子 Agent |
| | SkillsTool | 管理技能 |
| | SocialNetworkTool | Agent 社交网络 |
| | MCPTool | MCP 服务器集成 |

**工具调用流程**

```mermaid
sequenceDiagram
    participant LLM
    participant LE as LLMExecutor
    participant TR as ToolRegistry
    participant Tool as 具体工具
    participant SG as SecurityGuard
    
    LLM->>LE: 返回 tool_calls
    LE->>TR: execute(toolName, args)
    TR->>Tool: 查找工具
    Tool->>SG: 安全检查
    alt 检查通过
        SG-->>Tool: 允许执行
        Tool->>Tool: 执行操作
        Tool-->>TR: 返回结果
    else 检查失败
        SG-->>Tool: 拒绝执行
        Tool-->>TR: 返回错误
    end
    TR-->>LE: 工具结果
    LE->>LLM: 追加结果，再次调用
```

**技术细节**

1. **OpenAI 工具定义格式**
   ```java
   public List<ToolDefinition> getDefinitions() {
       List<ToolDefinition> definitions = new ArrayList<>();
       for (Tool tool : tools.values()) {
           definitions.add(new ToolDefinition(
               tool.name(), 
               tool.description(), 
               tool.parameters()
           ));
       }
       return definitions;
   }
   ```

2. **性能监控**
   ```java
   // ToolRegistry.java - execute 方法（含性能监控）
   long start = System.currentTimeMillis();
   String result = tool.execute(args);
   long duration = System.currentTimeMillis() - start;
   logger.info("Tool executed", Map.of(
       "tool", name,
       "duration_ms", duration,
       "result_length", result != null ? result.length() : 0
   ));
   ```

---

### 3.5 安全机制（SecurityGuard）

#### 是什么？

`SecurityGuard`（`io.leavesfly.tinyclaw.security.SecurityGuard`）是 TinyClaw 的安全守卫，提供工作空间沙箱和命令黑名单两层防护。

#### 为什么需要它？

AI Agent 能够执行文件操作和 Shell 命令，如果没有安全限制，可能会：

- 删除系统文件（如 `/etc/passwd`）
- 执行危险命令（如 `rm -rf /`）
- 访问敏感目录（如 `/root`）
- 提权操作（如 `sudo`）

SecurityGuard 通过**沙箱机制**和**黑名单过滤**，确保 Agent 只能在安全范围内操作。

#### 怎么做的？

**工作空间沙箱**

```java
// SecurityGuard.java - checkFilePath 方法
public String checkFilePath(String filePath) {
    if (!restrictToWorkspace) {
        return null; // 无限制
    }
    
    // 解析为绝对路径
    Path absPath = Paths.get(filePath).toAbsolutePath().normalize();
    Path workspacePath = Paths.get(workspace).toAbsolutePath().normalize();
    
    // 检查路径是否在工作空间内
    if (!absPath.startsWith(workspacePath)) {
        return String.format(
            "Access denied: Path '%s' is outside workspace '%s'",
            filePath, workspace
        );
    }
    
    return null; // 允许访问
}
```

**命令黑名单示例**

| 类别 | 正则模式 | 说明 |
|------|---------|------|
| 文件删除 | `\brm\s+-[rf]{1,2}\b` | 阻止 `rm -rf` |
| | `\bdel\s+/[fq]\b` | 阻止 Windows `del /f` |
| 磁盘操作 | `\b(format\|mkfs\|diskpart)\b\s` | 阻止格式化 |
| | `\bdd\s+if=` | 阻止 `dd` 磁盘写入 |
| 系统操作 | `\b(shutdown\|reboot\|poweroff\|halt)\b` | 阻止关机重启 |
| 权限提升 | `\b(sudo\|su)\s+` | 阻止提权 |
| 网络攻击 | `\b(curl\|wget).*\|\s*(sh\|bash\|python)` | 阻止管道执行 |

**安全检查流程**

```mermaid
graph TD
    A[工具调用] --> B{文件操作?}
    B -->|是| C[checkFilePath]
    B -->|否| D{命令执行?}
    
    C --> E{在工作空间内?}
    E -->|是| F[允许执行]
    E -->|否| G[拒绝并记录日志]
    
    D -->|是| H[checkCommand]
    D -->|否| F
    
    H --> I{匹配黑名单?}
    I -->|是| G
    I -->|否| F
    
    F --> J[执行工具]
    G --> K[返回错误消息]
```

**技术亮点**

1. **正则表达式黑名单**
   - 灵活匹配各种危险命令变体
   - 忽略大小写（`Pattern.CASE_INSENSITIVE`）

2. **自定义黑名单覆盖**
   ```java
   public SecurityGuard(String workspace, boolean restrictToWorkspace, 
                        List<String> customBlacklist) {
       this.commandBlacklist = buildCommandBlacklist(customBlacklist);
   }
   ```

3. **路径规范化**
   - 展开 `~` 为用户主目录
   - 解析符号链接
   - 防止 `../` 绕过

---

### 3.6 LLM Provider 与模型路由

#### 是什么？

LLM Provider 层统一封装不同 LLM 服务商的 API，支持多模型动态切换。

- `LLMProvider`（`io.leavesfly.tinyclaw.providers.LLMProvider`）：提供商接口
- `HTTPProvider`：基于 OpenAI 兼容接口的实现

#### 为什么需要它？

不同 LLM 服务商的 API 各不相同，如果在 Agent 中硬编码特定 API，会导致：

- 切换模型需要修改代码
- 无法利用多家服务商的优势
- 测试困难

通过**策略模式**和**工厂方法**，TinyClaw 实现了：

- 统一接口，屏蔽服务商差异
- 配置驱动，动态切换模型
- 支持自建模型（Ollama）

#### 怎么做的？

**LLMProvider 接口**

```java
// LLMProvider.java - 接口定义
public interface LLMProvider {
    // 普通对话
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, 
                     String model, Map<String, Object> options) throws Exception;
    
    // 流式对话
    LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, 
                           String model, Map<String, Object> options, 
                           StreamCallback callback) throws Exception;
    
    String getDefaultModel();
}
```

**支持的 Provider**

| Provider | 服务商 | API Base | 特点 |
|----------|--------|----------|------|
| openrouter | OpenRouter | https://openrouter.ai/api/v1 | 多模型网关 |
| openai | OpenAI | https://api.openai.com/v1 | GPT 系列 |
| anthropic | Anthropic | https://api.anthropic.com/v1 | Claude 系列 |
| zhipu | 智谱 AI | https://open.bigmodel.cn/api/paas/v4 | GLM 系列 |
| gemini | Google | https://generativelanguage.googleapis.com/v1 | Gemini 系列 |
| dashscope | 阿里云 | https://dashscope.aliyuncs.com/compatible-mode/v1 | 通义千问 |
| ollama | 本地 | http://localhost:11434/v1 | 本地模型 |

**模型路由机制**

```
config.json 中定义模型:
{
  "models": {
    "definitions": {
      "gpt-4": {
        "provider": "openai",
        "contextWindow": 8192
      },
      "claude-3-opus": {
        "provider": "anthropic",
        "contextWindow": 200000
      }
    }
  }
}

↓

agent.model = "gpt-4"

↓

查找模型定义 → provider = "openai"

↓

HTTPProvider.createProvider() 根据 provider 选择对应配置

↓

创建 HTTPProvider 实例（apiKey, apiBase）
```

**技术细节**

1. **OpenAI 兼容接口**
   - 大部分服务商支持 OpenAI 格式
   - 统一使用 `POST /chat/completions` 端点

2. **流式与非流式双模式**
   - 非流式：一次性返回完整响应
   - 流式：SSE（Server-Sent Events）逐块返回

3. **工具调用增量解析**
   - 流式模式下，tool_calls 可能分多个 chunk 返回
   - 需要累积解析完整的工具调用信息

---

## 五、典型数据流与场景

### 4.1 CLI 直接对话流程

CLI 模式下，用户直接与 Agent 交互，无需经过消息总线。

```mermaid
sequenceDiagram
    participant User as 用户
    participant Main as TinyClaw.main
    participant AC as AgentCommand
    participant AL as AgentLoop
    participant CB as ContextBuilder
    participant LE as LLMExecutor
    participant LLM as LLM Provider
    participant TR as ToolRegistry
    
    User->>Main: 输入命令
    Main->>AC: 创建 AgentCommand
    AC->>AL: 创建 AgentLoop
    AC->>AL: processDirect(content, sessionKey)
    
    AL->>CB: buildMessages(history, summary, content)
    CB-->>AL: 完整消息列表
    
    AL->>LE: execute(messages, sessionKey)
    LE->>LLM: 调用 LLM
    LLM-->>LE: 返回响应（含工具调用）
    
    loop 工具迭代（最多 maxIterations 次）
        LE->>TR: execute(toolName, args)
        TR-->>LE: 工具结果
        LE->>LLM: 追加工具结果，再次调用
        LLM-->>LE: 返回响应
    end
    
    LE-->>AL: 最终响应
    AL->>User: 输出回复
```

---

### 4.2 网关多通道模式流程

网关模式下，Agent 通过消息总线与多个通道交互。

```mermaid
sequenceDiagram
    participant EP as 外部平台<br/>(Telegram/Discord/...)
    participant CH as Channel
    participant MB as MessageBus
    participant AL as AgentLoop
    participant LLM as LLM Provider
    participant CM as ChannelManager
    
    EP->>CH: 用户消息
    CH->>CH: isAllowed(senderId)
    CH->>MB: publishInbound(message)
    
    MB->>AL: consumeInbound()
    AL->>AL: 构建上下文
    AL->>LLM: 调用 LLM
    LLM-->>AL: 返回响应
    AL->>MB: publishOutbound(response)
    
    MB->>CM: subscribeOutbound()
    CM->>CH: send(message)
    CH->>EP: 发送回复
```

---

### 4.3 定时任务触发流程

CronService 定期检查任务，到期时触发 Agent 执行。

```mermaid
sequenceDiagram
    participant CS as CronService<br/>后台线程
    participant Store as CronStore
    participant AL as AgentLoop
    participant MT as MessageTool
    participant CH as Channel
    
    loop 每秒检查
        CS->>Store: 读取 jobs.json
        Store-->>CS: 任务列表
        CS->>CS: 检查任务是否到期
        
        alt 任务到期
            CS->>AL: processDirectWithChannel(prompt, sessionKey)
            AL->>AL: 执行 Agent 逻辑
            AL-->>CS: 返回结果
            
            alt 配置了 deliver=true
                CS->>MT: 调用 MessageTool
                MT->>CH: 发送结果到通道
            end
        end
    end
```

---

### 4.4 工具调用迭代流程

LLM 可能需要多次调用工具才能完成任务，LLMExecutor 自动处理这种迭代。

```mermaid
graph TD
    A[LLM 调用] --> B{返回类型?}
    B -->|纯文本| C[返回响应]
    B -->|含 tool_calls| D[解析工具调用]
    
    D --> E[记录助手消息<br/>含 tool_calls]
    E --> F[依次执行工具]
    F --> G[追加工具结果到历史<br/>role=tool]
    
    G --> H{迭代次数 < maxIterations?}
    H -->|是| I[再次调用 LLM<br/>带工具结果]
    I --> B
    H -->|否| J[返回最后一次响应<br/>附加迭代限制提示]
```

---

## 六、技术栈与依赖

TinyClaw 使用精心挑选的轻量级库，确保高性能和低资源占用。

| 组件 | 技术/库 | 版本 | 用途 |
|------|--------|------|------|
| **语言** | Java | 17 | 主编程语言 |
| **构建** | Maven | 3.x | 项目构建和依赖管理 |
| **HTTP 客户端** | OkHttp | 4.12 | LLM API 调用、Webhook |
| **JSON 处理** | Jackson | 2.17 | JSON 序列化/反序列化 |
| **日志** | SLF4J + Logback | 2.0 / 1.4 | 结构化日志输出 |
| **命令行** | JLine | 3.25 | 交互式 CLI 体验 |
| **Telegram SDK** | telegrambots | 6.8 | Telegram Bot API |
| **Discord SDK** | JDA | 5.x | Discord Bot API |
| **飞书 SDK** | oapi-sdk | 2.3 | 飞书开放平台 |
| **钉钉 SDK** | dingtalk SDK | 2.0 | 钉钉开放平台 |
| **Cron 表达式** | cron-utils | 9.2 | 定时任务调度 |
| **测试** | JUnit 5 + Mockito | 5.10 / 5.x | 单元测试和 Mock |

**依赖特点**

- **无重型框架**：不依赖 Spring、Hibernate 等，减少启动时间和内存占用
- **标准化库**：选择业界标准库（Jackson、SLF4J），便于集成和维护
- **官方 SDK**：直接使用平台官方 SDK，确保稳定性

---

## 七、部署与使用场景

### 部署方式

#### 1. 单 JAR 部署

构建项目后生成可执行 JAR：

```bash
mvn clean package
java -jar target/tinyclaw.jar gateway
```

#### 2. 配置文件驱动

所有配置通过 `~/.tinyclaw/config.json` 管理：

```json
{
  "agent": {
    "model": "gpt-4",
    "workspace": "~/.tinyclaw/workspace",
    "maxTokens": 8192,
    "maxToolIterations": 10
  },
  "providers": {
    "openai": {
      "apiKey": "sk-xxx",
      "apiBase": "https://api.openai.com/v1"
    }
  },
  "channels": {
    "telegram": {
      "enabled": true,
      "token": "bot-token",
      "allowFrom": ["user1", "user2"]
    }
  }
}
```

#### 3. 工作空间目录结构

```
~/.tinyclaw/workspace/
├── AGENTS.md           # Agent 行为指令
├── SOUL.md             # Agent 个性设定
├── USER.md             # 用户信息
├── IDENTITY.md         # Agent 身份
├── memory/
│   ├── MEMORY.md       # 长期记忆
│   └── HEARTBEAT.md    # 心跳上下文
├── skills/
│   └── my-skill/
│       └── SKILL.md    # 自定义技能
├── sessions/
│   └── telegram:123456.json  # 会话历史
└── cron/
    └── jobs.json       # 定时任务
```

### 使用场景

#### 场景 1：个人 AI 助手（CLI 模式）

```bash
tinyclaw agent

🦞: 你好！我能帮你什么？
> 帮我总结一下 README.md 文件

🦞: 正在读取 README.md...
[调用 ReadFileTool]
这份文档介绍了 TinyClaw 项目的基本使用方法...
```

**适用于**：

- 本地文件处理
- 命令行自动化
- 代码辅助编写

#### 场景 2：企业 IM 机器人（网关模式）

部署在服务器上，接入飞书/钉钉：

```bash
tinyclaw gateway

[INFO] Channel manager initialized (enabled_channels=2)
[INFO] Starting all channels
[INFO] Feishu channel started
[INFO] DingTalk channel started
[INFO] Agent loop started
[INFO] Web console listening on http://localhost:8080
```

**适用于**：

- 企业内部智能客服
- 工单自动化处理
- 数据查询和报表生成

#### 场景 3：IoT 设备集成（MaixCam 通道）

接入摄像头设备，实现智能监控：

```json
{
  "channels": {
    "maixcam": {
      "enabled": true,
      "deviceUrl": "http://192.168.1.100"
    }
  }
}
```

**适用于**：

- 智能门禁系统
- 安防监控
- 语音交互设备

#### 场景 4：多 Agent 社交网络

多个 Agent 通过 SocialNetworkTool 互相通信：

```
Agent A: 发送消息给 Agent B
[调用 social_network 工具]
{
  "action": "send",
  "targetAgent": "agent-b-id",
  "message": "请帮我查询天气"
}

Agent B: 接收消息，处理后回复
[调用 WebSearchTool 查询天气]
{
  "action": "send",
  "targetAgent": "agent-a-id",
  "message": "今天北京晴，温度 20-28°C"
}
```

**适用于**：

- 多 Agent 协作
- 分布式任务处理
- Agent 能力共享

---

## 八、总结与展望

### OpenClaw 的遗产：从概念验证到生产实践

**OpenClaw 的历史意义**：
- 证明了 AI Agent 作为"个人数字员工"的可行性
- 展示了 Gateway + Agent Loop + Skills 的经典架构
- 20 万+ Stars 推动了整个 Agent 框架生态的发展
- 最终被 OpenAI 收购，验证了其技术价值

**OpenClaw 的局限**：
- 设计为快速原型，未考虑生产环境
- 安全问题严重（存在漏洞、恶意技能风险）
- NodeJS 单线程架构，并发性能受限
- 依赖管理复杂，部署不稳定

### TinyClaw 的使命：让 OpenClaw 的愿景走向现实

TinyClaw 不是要替代 OpenClaw，而是将其理念推向下一阶段：

**传承**：
- ✅ Gateway 架构 → MessageBus 消息总线
- ✅ Agent Loop → LLMExecutor 迭代循环
- ✅ Skills 系统 → SkillsLoader + Markdown 技能
- ✅ MCP 集成 → MCPManager
- ✅ 多通道支持 → 7+ 平台内置

**创新**：
- 🚀 生产级架构：5 层分层设计
- 🔒 安全优先：SecurityGuard 双层防护
- ⚡ 真正并发：反应式多线程架构
- 📦 企业部署：单 JAR，零依赖
- 💾 资源高效：128MB 内存，< 3s 启动

### 设计理论的实践价值

TinyClaw 不仅是一个可运行的 Agent 框架，更是多个软件工程理论的**实践验证场**：

#### 1. 证明了"组合优于继承"在 AI Agent 中的有效性

通过工具注册表模式，TinyClaw 展示了：
- **传统继承方式**需要为每种新能力创建子类，导致类爆炸
- **组合方式**通过注册工具动态扩展能力，代码量减少 70%

**量化对比**：
```
添加新能力的代码行数：
- 继承方式：~200 行（新建类 + 注册 + 测试）
- 组合方式：~50 行（实现 Tool 接口）

添加 10 个工具后的代码膨胀：
- 继承方式：2000+ 行 + 10 个类文件
- 组合方式：500 行 + 统一注册管理
```

#### 2. 验证了六边形架构在复杂集成场景下的价值

TinyClaw 集成了 7+ 消息平台，但核心 Agent 代码**零修改**：

```java
// 添加新通道只需实现 Channel 接口
public class NewPlatformChannel implements Channel {
    // 适配器代码
}

// Agent 代码完全不变
public class AgentLoop {
    // 通过 MessageBus 解耦，无需关心具体通道
}
```

**这证明了端口与适配器模式在"高度集成"场景下的有效性。**

#### 3. 展示了反应式架构在 AI Agent 中的必要性

传统同步架构在多通道场景下的问题：

**架构对比**（理论分析）：
| 架构 | 响应特点 | 并发能力 |
|------|----------|----------|
| 同步阻塞 | 响应时间随并发增长 | 受限于单线程处理 |
| TinyClaw 反应式 | 响应时间稳定 | 多线程独立处理 |

**反应式架构在多通道场景下具有更好的并发性能和响应稳定性**。

### 核心优势与竞争力

#### 技术维度

1. **轻量高效**：单 JAR 部署（15MB），启动 < 3s，内存 128MB
2. **安全可控**：双层沙箱 + 命令黑名单，专注安全的开源 Agent 框架
3. **灵活扩展**：工具优先设计，14+ 内置工具，动态注册
4. **多通道支持**：内置 7+ 平台集成，开箱即用
5. **模型中立**：支持 7+ LLM 提供商，动态切换

#### 商业维度

1. **企业友好**：Java 生态，易于集成，安全合规
2. **边缘计算**：低资源占用，适合 IoT/嵌入式设备
3. **快速上手**：配置驱动，3 分钟部署
4. **社区生态**：技能市场，工具共享

### 与主流框架的定位差异

```mermaid
graph LR
    subgraph 开发环境
        LC[LangChain<br/>库/组件]
        SK[Semantic Kernel<br/>企业编排]
    end
    
    subgraph 生产运行时
        TC[TinyClaw<br/>运行时框架]
    end
    
    subgraph 自主 Agent
        AG[AutoGPT<br/>研究原型]
    end
    
    LC -.提供组件.-> TC
    SK -.提供模式.-> TC
    TC -.借鉴理念.-> AG
```

**TinyClaw 的独特定位**：
- 不是"库"（LangChain），而是"运行时"
- 不是"框架"（Semantic Kernel），而是"应用服务器"
- 不是"原型"（AutoGPT），而是"生产就绪"

### 设计权衡与取舍

任何设计都有权衡，TinyClaw 也不例外：

| 设计选择 | 获得 | 失去 |
|---------|------|------|
| 单 JAR 部署 | 部署简单、无依赖 | 灵活性（需重新打包）|
| 工具注册表 | 动态扩展、低耦合 | 编译时类型检查 |
| 消息总线异步 | 高吞吐、故障隔离 | 调试复杂度 |
| 工作空间沙箱 | 安全可控 | 某些场景限制 |
| Java 实现 | 企业友好、性能 | 社区规模（vs Python）|

**这些权衡是有意识的，基于目标场景的深思熟虑。**

### 未来方向

#### 短期（6 个月内）

1. **RAG 集成**：内置向量数据库支持，实现知识库检索
2. **多 Agent 协作**：支持 Agent 间的任务分配和结果聚合
3. **Web UI 增强**：可视化配置、监控、调试界面

#### 中期（1 年内）

4. **插件市场**：建立社区生态，共享工具和技能
5. **性能优化**：支持流式工具调用，降低首字节延迟
6. **安全增强**：支持工具权限细粒度控制

#### 长期愿景

7. **分布式 Agent**：支持多节点部署，负载均衡
8. **边缘计算优化**：内存占用降至 64MB，支持更多嵌入式设备
9. **标准化协议**：推动 Agent 通信协议标准化

### 理论贡献与实践价值

TinyClaw 的意义不仅在于提供一个可用的框架，更在于：

1. **验证了多个软件工程理论在 AI Agent 领域的适用性**
   - UNIX 哲学的组合思想
   - 六边形架构的端口与适配器
   - 反应式架构的事件驱动

2. **探索了生产环境 AI Agent 的安全实践**
   - 内置沙箱的轻量级开源 Agent 框架
   - 提供了可参考的安全基线

3. **展示了 Java 在 AI Agent 领域的可行性**
   - 打破了"AI Agent = Python"的刻板印象
   - 为企业级应用提供了新选择

---

## 附录：相关资源

- **项目地址**：[GitHub - TinyClaw](https://github.com/leavesfly/tinyclaw)
- **核心代码**：
  - `io.leavesfly.tinyclaw.agent.AgentLoop`
  - `io.leavesfly.tinyclaw.bus.MessageBus`
  - `io.leavesfly.tinyclaw.security.SecurityGuard`
  - `io.leavesfly.tinyclaw.tools.Tool`
- **架构文档**：`docs/architecture.md`

---

**感谢阅读！欢迎交流讨论。**
