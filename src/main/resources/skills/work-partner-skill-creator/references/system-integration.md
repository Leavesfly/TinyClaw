# 系统集成参考手册

> **维护说明**：本文件由开发者维护，随企业系统和 MCP 接口的更新而迭代。  
> 当新的集成方式可用时，请在对应章节补充配置示例和调用方法。  
> 技能创建时将此文件原样复制到 `references/system-integration.md`，AI 工作伙伴按需读取。

---

## 一、业务系统访问（agent-browser）

通过 `agent-browser` 技能，AI 工作伙伴可以直接打开并操作用户在 `work-profile.md` 中配置的业务系统链接，无需用户手动打开浏览器。

### 使用前提

确认工作区已安装 `agent-browser` 技能，首次使用需执行：

```bash
agent-browser install
```

### 访问业务系统的标准步骤

```bash
# 1. 设置会话名称（建议使用系统名称）
export AGENT_BROWSER_SESSION=bi-dashboard

# 2. 打开系统链接（链接从 work-profile.md 中读取）
agent-browser --headed open https://[业务系统链接]

# 3. 获取页面快照
agent-browser snapshot -i

# 4. 提取关键数据
agent-browser get text @e1
```

### 常见业务系统集成示例

#### BI 数据看板 / 报表系统

```bash
export AGENT_BROWSER_SESSION=report
agent-browser --headed open [报表系统链接]
agent-browser snapshot -i
# 找到关键指标区域并提取
agent-browser get text body > /tmp/report_raw.txt
```

**AI 处理逻辑**：提取后对数据进行结构化解读，与昨日数据对比，生成趋势判断，保存至 `数据报表/[日期]_数据解读.md`。

#### CRM / 用户系统

```bash
export AGENT_BROWSER_SESSION=crm
agent-browser --headed open [CRM 链接]
agent-browser wait --load networkidle
agent-browser snapshot -i
```

#### 审批系统

```bash
export AGENT_BROWSER_SESSION=approval
agent-browser --headed open [审批系统链接]
agent-browser snapshot -i
# 查找"待审批"列表元素
agent-browser find text "待审批" click
agent-browser snapshot -i
```

### 注意事项

- 每次访问前检查 `work-profile.md` 中对应系统的链接是否有效
- 若链接需要登录，首次使用可通过 `agent-browser state save [系统名].json` 保存登录状态
- 相关文档参考：`agent-browser` 技能的 `references/authentication.md`

---

## 二、钉钉生态集成

### 2.1 钉钉文档（通过 aone MCP）

`aone` MCP 提供对钉钉文档的读取和写入能力。

**配置说明**

在工作区 MCP 配置中启用 `aone` 服务后，可通过以下方式访问：

```
# 读取钉钉文档内容
mcp__aone__get_doc_content(doc_url: "[文档链接]")

# 获取文档最新更新
mcp__aone__get_doc_updates(doc_url: "[文档链接]", since: "[时间戳]")

# 获取文档评论
mcp__aone__get_doc_comments(doc_url: "[文档链接]")
```

**在 Heartbeat 任务中的使用示例**（钉钉信息收集，08:30 执行）：

```
1. 读取 技能依赖.md 中的"钉钉配置 > 关注文档"列表
2. 对每个文档链接调用 mcp__aone__get_doc_updates
3. 汇总今日有更新的文档及变更摘要
4. 保存至 ~/[角色标识]-工作空间/临时文件/今日文档动态.md
```

**产出文件格式参考**：

```markdown
# 今日文档动态 [日期]

## [文档名称]
- 更新时间：[时间]
- 更新内容摘要：[AI 生成的摘要]
- 链接：[文档链接]

## [文档名称]
...
```

> **开发者维护**：aone MCP 接口名称可能随版本更新变化，请在此处同步更新。

---

### 2.2 钉钉日程与聊天（通过 dingtalk MCP）

`dingtalk` MCP 提供对钉钉日历、待办和聊天记录的访问能力。

**配置说明**

在工作区 MCP 配置中启用 `dingtalk` 服务后，可通过以下方式访问：

```
# 获取今日日程
mcp__dingtalk__get_calendar_events(date: "[今日日期]", user_id: "[用户ID]")

# 获取待办事项
mcp__dingtalk__get_todo_list(status: "pending")

# 获取聊天消息（指定群或联系人）
mcp__dingtalk__get_messages(conversation_id: "[会话ID]", since: "[时间戳]")

# 搜索消息关键词
mcp__dingtalk__search_messages(keyword: "[关键词]", date_range: "[范围]")
```

**在 Heartbeat 任务中的使用示例**（钉钉信息收集，08:30 执行）：

```
1. 调用 mcp__dingtalk__get_calendar_events 获取今日所有日程
2. 调用 mcp__dingtalk__get_todo_list 获取未完成待办
3. 整合生成今日工作安排摘要
4. 保存至 ~/[角色标识]-工作空间/临时文件/今日日程.md
```

**产出文件格式参考**：

```markdown
# 今日日程 [日期]

## 日程安排

| 时间 | 事项 | 参与人 | 备注 |
|------|------|--------|------|
| 10:00-11:00 | 周例会 | [参与人列表] | 需准备[内容] |
| 14:00-15:00 | 需求评审 | ... | ... |

## 待办事项

- [ ] [待办1]（截止：[日期]）
- [ ] [待办2]

## 今日重点提示

[AI 根据日程生成的工作重点提示]
```

> **开发者维护**：dingtalk MCP 的 `user_id` 和 `conversation_id` 从用户配置中读取。如需支持更多钉钉功能（如 DING 消息、审批流等），在此处补充对应接口说明。

---

## 三、邮件系统集成

### 3.1 通过 MCP 获取邮件数据

根据企业邮件系统类型，选择对应的 MCP 接口：

#### 企业微信邮件 / 网易邮箱（通用 IMAP）

```
# 获取今日未读邮件列表
mcp__email__get_unread(date: "[今日日期]", limit: 50)

# 获取邮件详情
mcp__email__get_message(message_id: "[邮件ID]")

# 搜索邮件
mcp__email__search(query: "[关键词]", date_from: "[日期]")

# 标记已读
mcp__email__mark_read(message_id: "[邮件ID]")
```

#### Exchange / Outlook（企业 AD 域）

```
# 获取收件箱
mcp__outlook__get_inbox(folder: "inbox", unread_only: true)

# 获取邮件线程
mcp__outlook__get_thread(thread_id: "[线程ID]")

# 发送邮件
mcp__outlook__send(to: "[收件人]", subject: "[主题]", body: "[正文]")
```

### 3.2 邮件数据处理与分析

**在 Heartbeat 任务中的使用示例**（邮件重要提醒，10:00 执行）：

```
1. 获取今日未读邮件列表
2. 按优先级规则筛选（规则来自 work-profile.md 中"邮件使用习惯"）：
   - 发件人在常用收件人列表中 → 高优先级
   - 主题含"紧急"、"urgent"、"ASAP" → 高优先级
   - 抄送我的邮件 → 中优先级
   - 订阅/通知类邮件 → 低优先级
3. 提取 Top 3 高优先级邮件摘要
4. 保存至 ~/[角色标识]-工作空间/邮件记录/[日期]_邮件摘要.md
```

**产出文件格式参考**：

```markdown
# 邮件摘要 [日期]

## ⚡ 需要及时处理（Top 3）

### 1. [邮件主题]
- **发件人**：[发件人]
- **时间**：[发送时间]
- **摘要**：[AI 生成的邮件内容摘要，2-3 句话]
- **建议动作**：[回复/转发/知悉/待办]

### 2. ...

## 📋 其他未读邮件（共 [N] 封）

[简要列表，只显示发件人和主题]
```

> **开发者维护**：实际使用的邮件 MCP 接口名称取决于企业邮件系统类型，请根据部署环境在此处确认正确的接口调用方式。

---

## 四、工作流程模板

### 4.1 活动复盘报告生成

**触发条件**：用户说"帮我写活动复盘"或"写一份复盘报告"

**标准生成步骤**：

```
步骤 1：收集数据
- 读取 数据报表/ 目录下活动期间的历史数据解读文件
- 如有邮件记录，检索活动相关邮件
- 如有钉钉文档链接，读取活动策划文档

步骤 2：应用复盘框架（参考 best-practices.md 中的相关方法论）
- 活动目标 vs 实际结果（数据对比）
- 执行过程关键节点回顾
- 亮点与问题分析
- 改进建议

步骤 3：生成报告
- 遵循用户在 work-profile.md 中记录的语言风格偏好
- 数据来源标注清晰
- 结论先于过程

步骤 4：输出
- 默认输出到对话中，询问用户是否需要保存到文件
- 如需保存：~/[角色标识]-工作空间/临时文件/活动复盘_[活动名称]_[日期].md
```

**复盘报告结构模板**：

```markdown
# [活动名称] 复盘报告

**活动周期**：[开始日期] ~ [结束日期]
**撰写日期**：[今日日期]

---

## 一、活动目标与实际结果

| 核心指标 | 目标值 | 实际值 | 达成率 |
|----------|--------|--------|--------|
| [指标1]  | [目标] | [实际] | [%]    |
| [指标2]  | ...    | ...    | ...    |

**整体结论**：[一句话结论]

## 二、执行过程回顾

[关键节点时间线，3-5 个重要节点]

## 三、亮点分析

[做对了什么，可复用的经验]

## 四、问题与不足

[出现了什么问题，根本原因分析]

## 五、改进建议

[下次如何做得更好，具体可执行的建议]
```

---

### 4.2 周报生成

**触发条件**：用户说"帮我写周报"或"生成本周工作总结"

**生成步骤**：

```
1. 读取本周（周一到今天）的工作日报文件：
   ~/[角色标识]-工作空间/工作日报/[日期]_工作日报.md
2. 读取本周的数据报表解读文件（提取核心指标趋势）
3. 按以下结构整合：
   - 本周核心成果（数据支撑）
   - 主要推进事项
   - 遇到的问题及解决方案
   - 下周计划
4. 风格遵循 work-profile.md 中的汇报偏好
```

---

### 4.3 数据早报生成

**触发条件**：心跳任务 09:00 自动触发，或用户说"帮我看今天的数据"

**生成步骤**：

```
1. 从 技能依赖.md 获取数据报表链接列表
2. 通过 agent-browser 访问每个报表，提取关键指标
3. 与昨日同文件对比，计算环比变化
4. 生成解读文字，标注异常波动（变化幅度 > 10% 或触及预警线）
5. 保存至 数据报表/[日期]_数据解读.md
```

---

### 4.4 日报生成（汇总型）

**触发条件**：心跳任务 22:00 自动触发，或用户说"帮我生成今天的日报"

**生成步骤**：

```
1. 读取今日临时文件（今日日程.md、今日文档动态.md）
2. 读取今日邮件摘要（邮件记录/[今日日期]_邮件摘要.md）
3. 读取今日数据解读（数据报表/[今日日期]_数据解读.md）
4. 结合 work-profile.md 中的工作职责背景整合归纳
5. 生成结构化日报，保存至 工作日报/[今日日期]_工作日报.md
```

**日报结构**：今日数据概览 → 重要邮件跟进 → 日程与会议回顾 → 明日工作建议

---

## 五、OKR 系统集成

### 5.1 通过 MCP 访问 OKR 数据

如果企业 OKR 系统提供了 MCP 接口，可通过以下方式读取 OKR 数据：

```
# 获取当前周期 OKR 列表
 mcp__okr__get_objectives(period: "[Q季度/年度]", user_id: "[用户ID]")

# 获取指定 Objective 的所有 KR
mcp__okr__get_key_results(objective_id: "[O 的 ID]")

# 获取 KR 的当前进度
mcp__okr__get_kr_progress(kr_id: "[KR ID]")

# 更新 KR 进度
mcp__okr__update_progress(kr_id: "[KR ID]", current_value: [value], comment: "[备注]")

# 获取团队成员 OKR
mcp__okr__get_team_objectives(team_id: "[团队 ID]", period: "[周期]")
```

**常见使用场景**：

```
# 场景 1：生成周报时自动关联 OKR 进度
1. 调用 mcp__okr__get_objectives 获取本周期 OKR
2. 对每个 KR 调用 mcp__okr__get_kr_progress
3. 在周报中输出当前进度和风险点

# 场景 2：季度复盘时生成 OKR 总结
1. 获取全季所有 KR 的最终达成率
2. 结合数据报表解读分析达成情况
3. 生成复盘报告
```

> **开发者维护**：各企业 OKR 系统（比如 Tita、Workpath、钉钉 OKR 模块、自研系统）的 MCP 接口名称不尽相同，请在此处确认实际使用的接口名。

---

### 5.2 通过 agent-browser 访问 OKR 系统

若 OKR 系统没有 MCP 接口，可通过 agent-browser 直接操作系统界面。

```bash
export AGENT_BROWSER_SESSION=okr

# 1. 打开 OKR 系统（链接从 work-profile.md 中读取）
agent-browser --headed open [企业 OKR 系统链接]
agent-browser wait --load networkidle

# 2. 定位并提取 OKR 内容
agent-browser snapshot -i
agent-browser find text "我的 OKR" click   # 按实际界面调整
agent-browser snapshot -i
agent-browser get text body > /tmp/okr_raw.txt
```

**提取后的 AI 处理逻辑**：

```
1. 解析原始文本，识别每个 Objective 和其下的 KR
2. 提取每个 KR 的当前进度数据（目标值 vs 当前实际值）
3. 计算达成率，标注滏后风险（达成率 < 70% 视为高风险）
4. 输出结构化摘要，格式见下方
```

**产出文件格式参考**：

```markdown
# OKR 进度快照 [日期]

## 周期：[Q1 2026 等]

### O1：[目标描述]

| KR | 目标值 | 当前实际值 | 达成率 | 状态 |
|----|--------|------------|--------|------|
| KR1：[描述] | [value] | [value] | [%] | ✅/⚠️/❌ |
| KR2：[描述] | ... | ... | ... | ... |

**整体进度**：[%]（超前/正常/滏后）

**风险提示**：[如有 KR 达成率 < 70%，列出并说明原因]
```

---

### 5.3 OKR 相关工作流程

#### 周报中自动带入 OKR 进度

**触发条件**：用户说“帮我写周报”

```
步骤 1：获取 OKR 状态
- 优先尝试 MCP 接口，若不可用则通过 agent-browser 访问
- 读取当前周期所有 KR 的最新进度

步骤 2：读取周内工作日报
- 获取近 5 天的工作日报文件

步骤 3：整合生成周报
- 本周核心成果（对应 KR 达成进展）
- KR 达成率更新
- 下周重点计划
```

#### 季度 OKR 复盘报告生成

**触发条件**：用户说“帮我写 OKR 复盘”或“季度总结”

```
步骤 1：获取全季 OKR 完整数据
步骤 2：读取本季度工作日报和数据解读文件（ 工作日报/ 和 数据报表/）
步骤 3：结合 best-practices.md 中的复盘方法论生成报告：
  - 各 Objective 达成率与分析
  - 亮点与不足
  - 下季度 OKR 廻建建议
```

---

## 六、开发者维护指南

### 如何添加新的系统集成

1. 在对应章节（业务系统 / 钉钉 / 邮件 / 其他）新增小节
2. 提供以下内容：
   - MCP 接口名称和调用示例
   - 在 Heartbeat 任务中的使用方式
   - 产出文件的格式参考
   - 注意事项（权限要求、配置前提）

### 如何扩展工作流程模板

在"第四章 工作流程模板"中新增小节，格式参考现有模板：
- 触发条件（用户语言或自动触发时间）
- 生成步骤（有序列表，步骤清晰）
- 产出文件路径规范
- 输出结构模板（可选）

### MCP 接口状态追踪

| 接口 | 状态 | 备注 |
|------|------|------|
| `mcp__aone__*` | 待验证 | 需确认企业 aone 服务是否开放 MCP |
| `mcp__dingtalk__*` | 待验证 | 需确认钉钉 MCP 接口版本 |
| `mcp__email__*` | 待验证 | 需根据企业邮件系统类型确认接口 |
| `mcp__okr__*` | 待验证 | 需确认 OKR 系统类型（Tita/Workpath/钉钉模块/自研）和实际接口名 |
| `agent-browser` | 可用 | 需要提前执行 `agent-browser install` |

> 接口可用性取决于用户的企业环境配置。若某接口不可用，AI 工作伙伴应告知用户并提供替代方案（如手动打开链接）。
