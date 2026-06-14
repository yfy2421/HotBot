# 热点追踪分析 Bot — 路线图

> 最后更新: 2026-06-05

---

## 当前状态

```
项目定位: 功能型原型 → 工程化 Bot → Agent 化智能助手（进行中）
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
| 渲染 | Java 2D 新闻卡片 (总览卡 + 详情卡) | ✅ |
| 持久化 | 会话状态文件落盘、重启恢复、语义匹配缓存 | ✅ |

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

### 1. Tool Calling 协议

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

### 2. 多步规划循环

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

### 3. 日志文件配置修正

**文件**: `bot-server\src\main\resources\logback-spring.xml`

Logback 日志格式 `%d{yyyy-MM-dd HH:mm:ss,SSS}` 中逗号被解析为时区名称导致"Unknown time-zone ID: SSS"错误，已修正为 `%d{yyyy-MM-dd HH:mm:ss.SSS}`。

---

## 中期规划 (3-4 周)

### 4. 分层 Memory 架构

**为什么做**: 当前只记 12 条消息 + 30 分钟快照。用户说"上次提过的那条"无法召回。需要分层记忆。

**要做的事**:

| 层 | 存什么 | 保留 | 实现 |
|---|-------|------|------|
| Working Memory | 当前 tool call 链的中间结果 | 单次请求 | 请求作用域 Map |
| Episodic Memory | 用户最近看过的新闻、话题 | 7 天 | ChromaDB 向量存储 |
| Semantic Memory | 用户长期偏好 (科技>娱乐) | 永久 | SQLite 或 ChromaDB |

- [ ] Episodic Memory: 每次用户看详情卡时写入 ChromaDB (id=用户+新闻id, vector=标题向量, metadata=时间戳)
- [ ] Semantic Memory: 统计用户 7 天内看过的新闻类别分布 → 写入用户画像
- [ ] 注入系统 prompt: "该用户偏好科技和时政类新闻，上次活跃在 6 月 3 日"
- [ ] "上次提过的" → 查 Episodic Memory → 命中 → 用命中的新闻 ID 做后续操作

**预期效果**: 用户说"上次那个AI芯片的还有后续吗" → 系统能定位到具体新闻

**文件**: `bot-server/src/main/java/com/bot/memory/` (新包), `ml-server/storage/memory_store.py`

---

### 5. 跨轮次上下文压缩

**为什么做**: 当前 FIFO 裁剪。对话超过 12 条就丢最早的。应该保留重要信息，压缩次要信息。

**要做的事**:

- [ ] 每轮对话结束时，用 LLM 生成一句话摘要
- [ ] 上下文 = 最近 6 条原始消息 + 历史摘要 (最多 500 字)
- [ ] 摘要包含: 用户问了什么、系统做了什么、关键发现

**文件**: `bot-server/src/main/java/com/bot/service/ContextCompressor.java`

---

## 长期规划 (1-2 月)

### 6. RSS 图片渲染到详情卡

**当前状态**: 有方案设计 (见 `.claude/plans/refactored-painting-tulip.md`)，待落地

**要做的事**:

- [ ] NewsItem 新增 imageUrl 字段
- [ ] NewsService 解析 RSS enclosure/media:content
- [ ] NewsCardRenderer 下载、缩放、绘制图片到详情长图

---

### 7. 通用化配置

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

---

## 迭代节律

```
每周:  review L2_L3_DISAGREE 日志 → 跑 improve_prototypes.py → 补 2-3 条原型
每月:  跑一轮 disagreement 分析 → LLM 诊断盲区 → 批量补原型
       review ROADMAP.md 进度 → 更新状态
```

---

## 相关文档

- [README.md](README.md) — 项目概览、配置、启动
- [CLAUDE.md](CLAUDE.md) — AI 助手的代码库指引
- [改进点.txt](改进点.txt) — 早期的问题分析笔记
- [方案.md](方案.md) — 早期整改方案
- [测试.md](测试.md) — 历史测试记录
