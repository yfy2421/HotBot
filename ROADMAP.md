# 热点追踪分析 Bot — 路线图

> 最后更新: 2026-06-15

---

## 当前状态

```
项目定位: Agent 化智能助手 (短期规划已完成，中期进行中)
技术栈:   Java 17 / Spring Boot 3.3 + Python 3.10 / FastAPI
测试:     93 Java + 21 Python (全部通过, CI 自动跑)
```

### 已完成

| 阶段 | 内容 | 状态 |
|------|------|------|
| 底座 | RSS 抓取、天气查询、微信 iLink 网关、多服务编排 | ✅ |
| 安全 | Circuit breaker、retry、fallback、多厂商 LLM 适配 | ✅ |
| 结构化 | God class 拆分为 8 个专职组件 | ✅ |
| 智能 | 三层意图路由 (L1 关键词 → L2 Embedding → L3 兜底) | ✅ |
| 体验 | 置信度驱动反问、CASUAL_CHAT 快速通道、日志持久化 | ✅ |
| 迭代 | 原型优化闭环 (improve_prototypes.py) | ✅ |
| 卡片渲染 | Java 2D 新闻卡片 + 排名渐变色徽章 + 斑马条纹 + 品牌水印 | ✅ |
| 持久化 | 会话状态文件落盘、重启恢复、语义匹配缓存 | ✅ |
| Agent | Tool Calling 协议 (Tool 接口 + 3 tool + toolLoop) | ✅ |
| Agent | 多步规划循环 (ReAct 指令 + 步骤计数器) | ✅ |
| 可靠性 | 推送去重 (MD5 签名) + 多源 API 回退 (fallbackUrls) | ✅ |

### 架构快照

```
用户 → 微信 iLink → weixin-gateway → bot-server (8080)
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
              IntentRouter          NewsSnapshotMgr      ConversationStateMgr
              (L1/L2/L3 路由)        (快照抓取+分类)       (对话状态+记忆)
                    │                     │                     │
                    ▼                     ▼                     ▼
              AssistantConversationService (主编排)
                    │
          ┌─────────┼─────────┐
          ▼         ▼         ▼
    DetailSelector  ReplyRenderer  NewsCardRenderer
    (三级匹配)       (回复拼装)      (PNG 卡片渲染)
                    │
                    ▼
              ml-server (5000)
              /api/chat | /api/intent/classify | /api/semantic/rank | /api/translate
```

---

## 短期规划 (1-2 周)

### 0. ✅ 卡片视觉打磨（借鉴 DailyHub）

**为什么做**: DailyHub 的 HTML 模板有精心设计的视觉系统（渐变徽章、斑马条纹、字体层级、水印页脚），本项目的 Java 2D 卡片偏"能用但不好看"。改进成本低（< 100 行）、效果明显。

**要做的事**:

- [ ] **排名渐变色徽章**: 总览卡前三条的序号用渐变色填充（#1 红橙 / #2 金黄 / #3 蓝青），第 4 条起灰色。`GradientPaint` 直接可用
- [ ] **交替行背景色**: 列表奇数行加极浅灰底 (`new Color(0,0,0,8)`)，偶数行白底，提升长列表可读性
- [ ] **页脚品牌水印**: 卡片底部居中加一行 `HotBot · 热点追踪`，小字号低透明度 (`new Color(0,0,0,60)`, 10pt)
- [ ] **字体层级调整**: 标题/正文/元信息三档字号拉开差距，标题加粗 (Font.BOLD)

**借鉴来源**: [astrbot_plugin_dailyhub](astrbot_plugin_dailyhub-master/) 的 `templates.py` 双主题 CSS 设计

**文件**: `bot-server/src/main/java/com/bot/service/NewsCardRenderer.java`

---

### 1. ✅ Tool Calling 协议

**为什么做**: 当前"一个意图 → 一个动作"的模型无法处理复合请求（如"找两条AI新闻分析一下"）。引入 Tool Calling 让 LLM 自主编排多步工具调用。

**要做的事**:

- [ ] 定义 `Tool` 接口: `name`, `description` (LLM 可读), `parameters` (JSON Schema), `execute(args)`
- [ ] 实现首批 Tool:
  - `FetchNewsTool`: 拉取新闻，支持 keyword/category/count 参数
  - `AnalyzeArticleTool`: 深度分析单条，调 ml-server commentary
  - `TrackFollowUpTool`: 查近 7 天后续，调 TrackingService
- [ ] 改造 `buildAiReply` 支持 tool calling 循环:
  ```
  LLM 输出 tool_call → Java 执行 → 结果追加到上下文
  → LLM 决定是否继续调 tool → 直到输出最终回复
  ```
- [ ] 超时与熔断: 单次 tool call 最多 3 轮，总超时 30s

**预期效果**: "找两条科技新闻分析一下" 从走 DEFAULT → LLM 自己拆成 fetch + analyze + synthesize

**文件**: `bot-server/src/main/java/com/bot/agent/` (新包)

---

### 2. ✅ 多步规划循环

**为什么做**: Tool Calling 让 LLM 能调工具，但没有显式的"先列计划，再逐步执行"。加上规划让 LLM 在复杂任务上更可靠。

**要做的事**:

- [ ] System prompt 增加 ReAct 风格指令:
  ```
  遇到复杂请求时，先列出执行计划，再逐步调用工具。
  每一步完成后检查结果，决定下一步或结束。
  ```
- [ ] 工具调用结果中注入"当前是第 N 步、还剩哪些步"
- [ ] 中间状态可视化: 用户可以看到"正在执行: 拉取新闻 → 分析中..."

**依赖**: 先完成 Tool Calling 协议

**预期效果**: "最近有什么大新闻？挑两条科技类分析一下，查查后续" 能被正确分解执行

---

### 3. ✅ 日志文件配置修正

**文件**: `bot-server\src\main\resources\logback-spring.xml`

Logback 日志格式 `%d{yyyy-MM-dd HH:mm:ss,SSS}` 中逗号被解析为时区名称导致"Unknown time-zone ID: SSS"错误，已修正为 `%d{yyyy-MM-dd HH:mm:ss.SSS}`。

---

### 4. ✅ 推送去重 + 多源 API 回退（借鉴 DailyHub）

**为什么做**: DailyHub 的 `SixtyClient` 为 API 配置了 5 个镜像自动回退，且推送前对比签名去重。本项目 RSS 源挂了就是挂了，推送也没有去重。

**要做的事**:

- [ ] **推送去重**: `NewsSnapshotManager` 推送前对比快照签名（标题列表 hash），签名未变则跳过本轮推送，避免连续两次推相同内容
- [ ] **RSS 源备用 URL**: 为关键源配置 1-2 个备用镜像（如 rsshub.app → rsshub.rssforever.com），`NewsService.fetchRss()` 失败时自动试下一个
- [ ] **降级日志**: 回退发生时写 WARN 日志，方便后续排查哪些源不稳定

**借鉴来源**: [astrbot_plugin_dailyhub](astrbot_plugin_dailyhub-master/) 的 `client.py`（多源回退）和 `scheduler.py`（签名去重）

**文件**: `bot-server/src/main/java/com/bot/service/NewsService.java`, `NewsSnapshotManager.java`, `application.yml`

---

## 中期规划 (3-4 周)

### 5. 分层 Memory 架构（借鉴 AstrBot 三层记忆插件）

**为什么做**: 当前只记 12 条消息 + 30 分钟快照。用户说"上次提过的那条"无法召回，bot 不记得用户偏好。AstrBot 记忆插件提供了完整的三层记忆参考实现——从 RIF 评分公式到升级降级条件到遗忘策略。

#### 5.1 三层存储

| 层 | 存什么 | 保留 | 实现 |
|---|-------|------|------|
| Working Memory | 当前 tool call 中间结果 + 本轮对话关键信息 | 单次请求 | 请求作用域 Map (LRU, 上限 20 条) |
| Episodic Memory | 用户看过的新闻、话题、问过的问题 | 7-90 天（按 RIF 评分动态淘汰） | ChromaDB 向量存储 |
| Semantic Memory | 用户长期偏好 (科技>娱乐)、核心特征 | 永久（有保护机制） | ChromaDB + SQLite 用户画像 |

#### 5.2 RIF 评分公式（决定记忆优先级）

```text
RIF = 0.4 × 时近性 + 0.3 × 相关性 + 0.3 × 频率

时近性权重:
  7 天内    → 1.2
  7-30 天   → 1.0
  30-90 天  → 0.8
  > 90 天   → 0.6

相关性: 向量检索的 cosine 相似度 (0-1)
频率:    该记忆被检索/命中的次数归一化
```

- [ ] RIF 评分计算函数 (`MemoryScorer.java`)
- [ ] 每次检索时更新命中记忆的 RIF 分数

#### 5.3 记忆流转规则

```
工作记忆 → 情景记忆（满足任一）:
  · 访问 ≥ 3 次 且 重要性 > 0.5
  · 情感强度 > 0.6
  · 置信度 ≥ 0.7
  · 用户主动保存

情景记忆 → 语义记忆（满足任一）:
  · 访问 ≥ 5 次 且 置信度 > 0.65
  · 重要性 ≥ 0.8 且 访问 ≥ 3 次
  · 存在 > 7 天 且 访问 ≥ 3 次 且 置信度 > 0.6
```

- [ ] `MemoryLifecycle` 类：检查升级/降级条件，执行流转
- [ ] 语义记忆永不自动降级（需显式删除）

#### 5.4 遗忘策略

```
新记忆 → 7 天宽限期 → 不会被删
  · 极低价值（置信度 < 0.3 + 零访问 + 低情感）→ 直接清除
  · 高价值（情感 ≥ 0.5 或 重要性 ≥ 0.6 且访问 ≥ 2）→ 自动保留
  · 中等 → 进入宽限期，7 天后自动清除
```

- [ ] 遗忘检查器 (`MemoryPruner.java`)：每日定时扫描，按条件清理

#### 5.5 记忆注入 LLM 上下文

每次请求自动检索相关记忆拼入 system prompt：

```
1. 用用户消息做向量检索 → 取 top-K 条相关记忆
2. 按 RIF 评分排序 → 取前 N 条（可配，默认 5）
3. 注入 system prompt:
   "关于该用户的已知信息:
    - 偏好科技和时政类新闻，上周详细阅读了 3 篇芯片相关文章
    - 上次活跃: 6 月 3 日，询问了英伟达 B300 芯片的后续
    - 沟通偏好: 喜欢直接的分析风格，不喜客套"
```

- [ ] `MemoryInjector` 类：检索 + 排序 + 格式化注入文本
- [ ] 修改 `buildSystemPrompt()` 追加用户画像段落

#### 5.6 用户可见的记忆命令

```
/memory stats              → 当前会话记忆统计（各层数量）
/memory search <关键词>     → 语义检索已保存的记忆
/memory save <内容>        → 手动保存（最高置信度，永不降级）
/memory delete             → 删除当前会话记忆
```

- [ ] 在 `IntentKeywords` 和 `IntentRouter` 注册 `/memory` 命令
- [ ] `MemoryCommandHandler`：解析子命令 → 执行 → 格式化回复

**预期效果**:

- 用户说"上次那个AI芯片的还有后续吗" → bot 能定位到具体新闻
- 用户说"最近有什么新闻" → 优先展示科技类（因为记住了偏好）
- 用户说"今天心情不好" → bot 能想起之前的情绪状态，调整回复风格
- `/memory search 芯片` → 用户能感知到 bot 记住了什么

**文件**: `bot-server/src/main/java/com/bot/memory/` (新包), `ml-server/storage/memory_store.py`

**借鉴来源**: AstrBot 三层记忆插件的 RIF 评分公式、流转条件、遗忘宽限期、用户命令设计

---

### 6. 跨轮次上下文压缩

**为什么做**: 当前 FIFO 裁剪。对话超过 12 条就丢最早的。应该保留重要信息，压缩次要信息。

**要做的事**:

- [ ] 每轮对话结束时，用 LLM 生成一句话摘要
- [ ] 上下文 = 最近 6 条原始消息 + 历史摘要 (最多 500 字)
- [ ] 摘要包含: 用户问了什么、系统做了什么、关键发现

**文件**: `bot-server/src/main/java/com/bot/service/ContextCompressor.java`

---

## 长期规划 (1-2 月)

### 7. RSS 图片渲染到详情卡

**当前状态**: 有方案设计 (见 `.claude/plans/refactored-painting-tulip.md`)，待落地

**要做的事**:

- [ ] NewsItem 新增 imageUrl 字段
- [ ] NewsService 解析 RSS enclosure/media:content
- [ ] NewsCardRenderer 下载、缩放、绘制图片到详情长图

---

### 8. 内容类型策略模式（借鉴 DailyHub `kinds.py`）

**为什么做**: DailyHub 的 6 个 `Kind` 子类封装了每种内容类型的全部行为（归一化、文字渲染、HTML 上下文、去重签名、短链收集）。新增类型只需加一个子类。目前本项目的新闻分类和渲染逻辑嵌在 `NewsCardRenderer` 和 `NewsItem` 里，新增非新闻类型（如数据卡片、榜单）需要改多处。

**要做的事**:

- [ ] 抽象 `ContentKind` 接口: `toText(raw)` / `renderContext(raw)` / `dedupSignature(raw)`
- [ ] 现有新闻类型实现为 `NewsKind`，未来新增（如天气数据卡、热搜榜）各自为独立 Kind
- [ ] `ReplyRenderer` 按源的 `renderKind` 分发，不再 hardcode "新闻只有一种渲染方式"

**借鉴来源**: [astrbot_plugin_dailyhub](astrbot_plugin_dailyhub-master/) 的 `kinds.py`（策略模式）和 `templates.py`（模板-策略绑定）

**文件**: `bot-server/src/main/java/com/bot/service/kind/` (新包)

---

### 9. 通用化配置

**为什么做**: 当前 RSS 源、推送目标、天气城市都硬编码。改成可配置。

**要做的事**:

- [ ] RSS 源管理 API: CRUD `/api/admin/feeds`
- [ ] 推送目标配置: 支持多微信/QQ 目标
- [ ] 简单 Web 管理面板

---

## 不做的事

| 项目 | 原因 |
|------|------|
| Multi-Agent 协作 | 单用户单任务场景，不需要多个 Agent 协商。过度设计。 |
| RLHF / 在线学习 | 没有足够的用户反馈数据。离线原型迭代已足够。 |
| LangChain / CrewAI 框架 | 引入了不必要的依赖和抽象层。150 行自研 Tool 协议比 5000 行框架适配更可控。 |
| 群聊支持 | 微信 iLink 群聊 API 受限，且会话管理复杂度大幅上升。私聊场景已够用。 |
| 知识图谱 | 新闻 bot 的实体关系太稀疏（用户→新闻→类别），三元组提取和多跳推理是杀鸡用牛刀。 |
| 大五人格分析 | 用户画像够用的就是"偏好科技>娱乐"维度和沟通风格，不需要 5 个维度 30 个特征的人格模型。 |
| 图片分析 (Vision LLM) | 用户发的图片基本是表情包，不需要视觉分析。 |
| 主动回复 / 场景自适应 | 我们已有时推送，且是私聊场景，不需要群活跃度感知。 |
| Web 管理面板（独立前端） | 独立 FastAPI 端口 + 前端页面工作量大，单用户 bot 收益有限。 |

---

## 迭代节律

```
每周:  review L2_L3_DISAGREE 日志 → 跑 improve_prototypes.py → 补 2-3 条原型
每月:  跑一轮 disagreement 分析 → LLM 诊断盲区 → 批量补原型
       review ROADMAP.md 进度 → 更新状态
```

---

## 参考对比: AstrBot DailyHub 插件

[astrbot_plugin_dailyhub-master/](astrbot_plugin_dailyhub-master/) 是本项目的对标参考项目。

**它比本项目好的**:

- 策略模式解耦内容类型（`kinds.py` 6 个 Kind）→ 已纳入长期规划 #7
- 多源 API 回退（5 个镜像自动切换）→ 已纳入短期规划 #4
- 推送内容签名去重 → 已纳入短期规划 #4
- 逐层降级链（图片→文字，LLM→原文，短链→长链）
- HTML/CSS 双主题渲染（渐变徽章、斑马条纹、字体层级）→ 已纳入短期规划 #0
- 订阅模型（每源独立推送目标）

**本项目比它好的**:

- AI 工程深度（Embedding 分类、CrossEncoder、NER、翻译、语义匹配）
- Java 2D 原生卡片渲染（不依赖外部 t2i 服务）
- 多轮对话 + 快照持久化 + 聚焦条目跟踪
- 微信 iLink 私聊直连（非框架寄生）
- 详情卡原文摘录 + AI 点评（非标题列表）
- 114 测试 + CI（对方 0 测试）

**它不值得借鉴的**:

- HTML→图片依赖远程 t2i 服务（我们的 Java 2D 更可控）
- 9/10 数据源依赖单一 60s API（我们的自抓 RSS 更独立）
- 无会话管理（无状态 vs 我们的多轮上下文）

---

## 参考对比: AstrBot 三层记忆插件

AstrBot 社区的三层记忆插件提供了完整的记忆管理参考实现。

**值得借鉴并已纳入规划**:

- RIF 评分公式（0.4×时近性 + 0.3×相关性 + 0.3×频率）→ 已纳入中期规划 #5.2
- 记忆升级/降级流转规则（明确的触发条件和阈值）→ 已纳入中期规划 #5.3
- 惰遗忘宽限期（7 天保护 + 极低价值直接清除 + 高价值保留）→ 已纳入中期规划 #5.4
- 记忆注入 LLM 上下文（每次请求自动检索相关记忆拼入 system prompt）→ 已纳入中期规划 #5.5
- 用户可见的记忆命令（/memory save/search/stats/delete）→ 已纳入中期规划 #5.6

**不借鉴**:

- 知识图谱——新闻 bot 实体关系太稀疏，三元组提取和多跳推理不必要
- 大五人格分析——偏好+沟通风格两个维度已够
- Web 管理面板——单用户 bot 不需要独立前端
- 主动回复/场景自适应——我们已有定时推送
- 图片分析 (Vision)——用户发的图片基本是表情包
- 群聊隔离/人格隔离——我们只做私聊

---

## 相关文档

- [README.md](README.md) — 项目概览、配置、启动
- [CLAUDE.md](CLAUDE.md) — AI 助手的代码库指引
- [改进点.txt](改进点.txt) — 早期的问题分析笔记
- [方案.md](方案.md) — 早期整改方案
- [测试.md](测试.md) — 历史测试记录
