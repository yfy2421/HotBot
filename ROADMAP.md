# 热点追踪分析 Bot — 路线图

> 最后更新: 2026-06-15

---

## 当前状态

```
模式:     收敛模式 — 短期已交付, 中期暂停, 消化现有功能后再推进
定位:     具备 Tool Calling + 三层意图路由的新闻助手
技术栈:   Java 17 / Spring Boot 3.3 + Python 3.10 / FastAPI
测试:     121 Java + 21 Python (全部通过, CI 自动跑)

Agent 化进度: Plan 80% · Tool 80% · Memory 10%
下一步:     收敛而非扩张 — 文档对齐、演示脚本、真实样例评测
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

## 收敛任务 (当前)

> 消化现有功能, 不新增功能线。做完这些再继续中期规划。

| # | 任务 | 状态 |
|---|------|------|
| C1 | README 同步代码实际能力 | ✅ done |
| C2 | ROADMAP 标记真实进度 | ✅ done |
| C3 | 质疑文档标记已验证项 | ⬜ |
| C4 | 演示脚本 (30 秒核心交互) | ⬜ |
| C5 | 真实样例评测 (30 条中文输入) | ⬜ |

---

## 短期规划 (已完成)

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

### 0b. 卡片超采样渲染（借鉴真寻日报）

**为什么做**: 真寻日报用 Playwright `device_scale_factor=5` 做 5 倍超采样，截图再缩回，文字边缘清晰度远超 1x 渲染。Java 2D 可同理：创建 2x `BufferedImage`，全部坐标和字体 ×2，画完 `getScaledInstance` 缩回目标尺寸。

**要做的事**:

- [ ] 2x 渲染模式: `renderScale = 2.0`，`BufferedImage(width*2, height*2)` → 画完 → `Image.getScaledInstance(width, height, SCALE_SMOOTH)`
- [ ] 可选开关: 超采样增加内存占用（2x 时 ×4），对超大详情卡可能 OOM，保留 1x 回退

**借鉴来源**: [astrbot_plugin_zhenxunribao](astrbot_plugin_zhenxunribao-master/) 的 `_render_html_with_playwright()` DPR 机制

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

## 中期规划 (暂停, 待收敛完成后评估)

### 5. 双轨 Memory 架构

**为什么做**: 当前只记 12 条消息 + 30 分钟快照。本 Bot 同时承载新闻交互和社交交互两种职责，不能用同一套评分公式管。

**核心洞察**: 聊天 Bot 的记忆是同一类东西（"关于这个用户的 facts"），新闻 Bot 的记忆是两种完全不同的东西——新闻浏览记录（时效强，14 天后基本无用）和用户偏好/社交互动（无时效，随时间累积更准）。需要双轨制。

**设计原则**:

```
用户消息
    │
    ├─ 新闻交互 (说新闻/分析/追踪) → 新闻记忆轨道
    │   ├─ 浏览记忆 (被动，3-21天淘汰，交互深度评分)
    │   └─ 追踪记忆 (主动，不过期，三层触发)
    │
    └─ 社交交互 (闲聊/情绪/偏好) → 社交记忆轨道
        └─ RIF 评分 + 7-90天流转 + 情感衰减
```

---

#### 5.1 轨道 A: 新闻浏览记忆

**存什么**: 用户看过的新闻标题、交互深度、时间戳。用 ChromaDB 向量存储，按标题向量检索。

**评分——交互深度（不是访问频率）**: 新闻 Bot 里用户看一条新闻通常只看一次。频率不说明重要性，交互深不深才说明。

```text
NewsMemoryScore = 0.55 × 时近性 + 0.25 × 交互深度 + 0.20 × 相关性

时近性（天）:
  1 天       → 1.0
  1-3 天     → 0.85
  3-7 天     → 0.6
  7-14 天    → 0.35
  > 21 天    → 淘汰（显式取消前不保留）

交互深度:
  只看标题列表         → 0.3    看了就划走
  切到详情卡看了       → 0.6    花时间读了
  要求了 AI 分析       → 0.8    深度阅读
  读了原文 + 分析      → 1.0    最高投入，可能后续跟进
```

**淘汰——按交互深度分级（不是一刀切）**:

| 交互深度 | 淘汰时间 | 逻辑 |
|---------|---------|------|
| 只看标题 (0.3) | 3 天 | 划走就忘 |
| 切详情卡 (0.6) | 7 天 | 投入了时间 |
| AI 分析 (0.8) | 14 天 | 深度阅读 |
| 原文+分析 (1.0) | 21 天 | 最高投入 |

**不升级、不降级、不流转**。浏览记忆永远是浏览记忆——14 天一到就淘汰。不需要"访问 3 次升级到语义记忆"——新闻场景下用户不会反复看同一条。

- [ ] `NewsMemoryStore`: ChromaDB 写入/检索/评分更新
- [ ] `NewsMemoryPruner`: 每日定时扫描，按交互深度分级淘汰
- [ ] 交互深度自动更新: 用户对同一条新闻的后续操作（"分析"→0.8, "原文"→1.0）覆盖之前的分数

---

#### 5.2 轨道 A: 新闻追踪记忆

**为什么需要**: 浏览记忆会被淘汰（21 天），但用户明确要求"盯着这个"的新闻不应该过期。和浏览记忆分开管理。

**三层触发机制**:

```
Layer 1 — 被动匹配 (每天自动):
  新新闻入库 → 和浏览记忆做向量匹配 → 相似度 > 阈值
  → 推送 "你可能感兴趣: XX 有后续——《新标题》"
  → 仅当浏览记忆在有效期内才匹配

Layer 2 — 显式追踪 (用户标记/不过期):
  用户说 "盯着"、调用了 track_follow_up、或 Layer 3 自动标记
  → 写入追踪标记 (关键词 + 向量 + active=true, 不过期)
  → 每天新新闻匹配 → 命中就 @用户推送
  → 用户说 "不用盯了" → active=false

Layer 3 — 主动查询 (用户问 "现在怎样了"):
  用户问 → 查 RSS 有没有相关新新闻
  → 有 → 回复 + 不额外写追踪 (如果已有标记)
  → 无 → 调 LLM 搜索总结 → 回复 + 自动写入 Layer 2 追踪标记
  → 都搜不到 → 诚实说 "暂无后续, 已标记持续关注"
```

**追踪标记数据结构 — 话题簇**:

每次新新闻匹配成功时，新新闻也加入追踪存储，形成完整的事件链：

```json
{
    "user_id": "weixin:o9cq80...",
    "type": "tracking",
    "topic_id": "track-nvidia-b300",        // 话题唯一标识
    "keywords": ["英伟达", "B300", "芯片"],
    "chain": [
        {
            "news_id": "news-20260603-001",
            "title": "英伟达发布B300芯片",
            "role": "origin",               // 原始触发
            "added_at": "2026-06-03"
        },
        {
            "news_id": "news-20260610-003",
            "title": "B300首批交付延期",
            "role": "follow_up",            // 匹配到的后续
            "added_at": "2026-06-10",
            "similarity": 0.87
        },
        {
            "news_id": "news-20260705-002",
            "title": "B300大规模量产启动",
            "role": "follow_up",
            "added_at": "2026-07-05",
            "similarity": 0.82
        }
    ],
    "match_count": 2,                       // 匹配到几条后续
    "created_at": "2026-06-03",
    "last_matched_at": "2026-07-05",
    "active": true
}
```

**话题簇的好处**:
- 用户问"芯片那个后来怎么样了" → 直接列出 chain 里所有 follow_up 条目，形成完整时间线
- chain 里的所有新闻都不过期——不管原新闻还是匹配到的后续，都跟着追踪标记一起保留
- 可以展示事件演进: "发布 → 交付延期 → 量产启动"

**完整时间线示例**:

```
6/3  用户看了《英伟达 B300 发布》→ 浏览记忆 (深度 0.6, 7天)
     用户说 "盯着这个" → 追踪记忆写入 (不过期)

6/10 RSS 来《B300 首批交付延期》
      → Layer 2 命中: 追踪标记匹配 → @用户推送 "你关注的英伟达芯片有进展"
      → Layer 1: 浏览记忆仍在 7 天内 → 不重复推

6/17 浏览记忆过期 (>7天)

6/25 用户说 "那个芯片后面怎么样了"
      → Layer 3: 查 chain → 列出完整时间线:
        "你关注的《英伟达B300芯片》进展:
         6/3: 英伟达发布B300芯片
         6/10: B300首批交付延期
         有没有新动态?"
      → 查 RSS → 有《B300 量产》→ 加入 chain (role=follow_up)
      → 回复: "有新动态: B300量产启动。现在chain里有3条记录了。"

7/20 RSS 来《B300 被禁售》
      → Layer 2 命中: 追踪标记活跃 → @用户推送
      → 同时加入 chain (role=follow_up), match_count 变为 3
```

- [ ] `TrackingMemoryStore`: ChromaDB 写入/检索追踪标记
- [ ] `DailyTrackingMatcher`: 每天新新闻入库后, 分别跑 Layer 1 + Layer 2 匹配
- [ ] `TrackingCommandHandler`: `/追踪 关键词` `/取消追踪` 命令
- [ ] 追踪标记不参与淘汰——用户显式取消前永不过期

---

#### 5.3 轨道 B: 社交记忆（RIF 评分 + 三级流转）

**为什么保留 RIF**: 社交交互和聊天 Bot 的场景完全一致。用户说"喜欢咖啡"、"养猫"、"早上打招呼"——这些需要长期保留，频繁访问应该加分，RIF 公式适用。

```text
RIF = 0.4 × 时近性 + 0.3 × 相关性 + 0.3 × 频率

时近性权重:
  7 天内    → 1.2
  7-30 天   → 1.0
  30-90 天  → 0.8
  > 90 天   → 0.6
```

**流转规则（仅社交记忆）**:

```
工作记忆 → 情景记忆 (满足任一):
  · 访问 ≥ 3 次 且 重要性 > 0.5
  · 情感强度 > 0.6
  · 置信度 ≥ 0.7
  · 用户主动保存 (/memory save)

情景记忆 → 语义记忆 (满足任一):
  · 访问 ≥ 5 次 且 置信度 > 0.65
  · 重要性 ≥ 0.8 且 访问 ≥ 3 次
  · 存在 > 7 天 且 访问 ≥ 3 次 且 置信度 > 0.6
```

**遗忘（仅社交记忆）**:

```
新记忆 → 7 天宽限期
  · 极低价值 (置信度 < 0.3 + 零访问 + 低情感) → 直接清除
  · 高价值 (情感 ≥ 0.5 或 重要性 ≥ 0.6 且 访问 ≥ 2) → 自动保留
  · 中等 → 宽限期后清除

情感差异衰减:
  · 正面情感 → 慢衰减 (~60 天半衰期)
  · 负面情感 → 快衰减 (避免消极情绪长期影响)
  · 中性     → 标准衰减
```

- [ ] `SocialMemoryStore`: ChromaDB 写入/检索/RIF 评分更新
- [ ] `MemoryLifecycle`: 检查升级/降级条件，执行流转
- [ ] `MemoryPruner`: 每日扫描，按条件清理 + 情感衰减

---

#### 5.4 Working Memory（跨轨道共享）

**存什么**: 当前 tool call 链的中间结果 + 本轮对话关键信息。跨两条轨道共享——不管是新闻交互还是社交交互，当前轮次的工作状态都在这里。

**实现**: 请求作用域 `Map` (LRU, 上限 20 条)。请求结束即清空。

---

#### 5.5 记忆注入 LLM 上下文

每次请求从两条轨道分别检索，合并注入 system prompt：

```
1. 新闻记忆检索: 用当前消息向量查浏览记忆 + 追踪记忆 → 取 top-3
2. 社交记忆检索: 用当前消息向量查社交记忆 → 取 top-3
3. 用户偏好: 从社交记忆统计类别分布
4. 合并注入:
   "关于该用户的已知信息:
    新闻方面:
    - 3 天前深入阅读了《英伟达B300发布》(交互深度 0.8)
    - 正在追踪: 英伟达芯片后续 (已匹配 2 次)
    偏好方面:
    - 科技 > 时政, 喜欢直接的分析风格
    - 习惯早上打招呼"
```

- [ ] `MemoryInjector`: 双轨道检索 + 合并排序 + 格式化注入
- [ ] 修改 `buildSystemPrompt()` 追加用户记忆段落

---

#### 5.6 用户可见的命令

```
/memory stats              → 当前会话记忆统计 (浏览/追踪/社交 各多少条)
/memory search <关键词>     → 语义检索所有记忆
/memory save <内容>        → 手动保存到社交记忆 (最高置信度，永不降级)
/memory delete             → 删除当前会话社交记忆
/追踪 <关键词>              → 写入追踪记忆 (不过期)
/取消追踪                   → 取消当前聚焦条目的追踪
```

- [ ] `MemoryCommandHandler`: 解析子命令 → 执行 → 格式化回复
- [ ] 在 `IntentKeywords` 和 `IntentRouter` 注册命令

---

#### 5.7 自动注册推送订阅（借鉴真寻日报）

**为什么做**: 真寻日报首次 `/日报` 命令自动记录群组 ID，后续定时推送无需手动配置。当前本项目推送目标硬编码，应改为首次交互时自动订阅。

**要做的事**:

- [ ] 用户首次发消息时自动写入推送订阅表（conversation_id + 注册时间）
- [ ] `/订阅` `/取消订阅` 允许用户管理自己的推送状态
- [ ] 调度器改为读取订阅表而非硬编码目标列表

**借鉴来源**: [astrbot_plugin_zhenxunribao](astrbot_plugin_zhenxunribao-master/) 的自动学习群组映射机制

**文件**: `bot-server/src/main/java/com/bot/scheduler/DailyBotScheduler.java`, 新增 `PushSubscriptionStore.java`

---

#### 5.8 预期效果

| 场景 | 之前 | 之后 |
|------|------|------|
| "上次芯片那条还有后续吗" | "不确定你指哪条" | 浏览记忆检索 → 命中 → 查后续 |
| "盯着这个" | 不支持 | 追踪记忆写入 → 每天新新闻匹配 → 自动推送 |
| "最近有什么新闻" | 全列, 不分偏好 | 社交记忆统计偏好 → 科技 > 娱乐 |
| 新新闻《B300量产》入库 | 正常推送 | Layer 2 匹配追踪标记 → @关注用户 |
| "/memory stats" | 不支持 | 浏览 5 条 / 追踪 2 条 / 社交 8 条 |

**文件**: `bot-server/src/main/java/com/bot/memory/` (新包), `ml-server/storage/memory_store.py`

**借鉴来源**: AstrBot 三层记忆插件的 RIF 公式与流转规则（社交轨道）, DailyHub 的去重推送（追踪 Layer 1）, 真寻日报的自动注册（5.7）

---

#### 5.9 存储优化措施

**为什么需要优化**: 三条轨道分开存储带来冗余、延迟、合并复杂度。以下 5 条措施针对性解决，每条有明确代价。

| # | 优化 | 代价 | 缓解 |
|---|------|------|------|
| 1 | **ID 去重**: 合并时用 `news_id` 判等而非标题相似度 | 跨源同内容可能漏去重 | 加一层标题相似度兜底 (>0.95) |
| 2 | **单向量多索引**: 新闻向量只在 `news_memory` 存一份，追踪标记用 `metadata.tracked=true`，话题簇存 SQLite | ChromaDB metadata 不支持复杂查询 + 耦合风险 | 复杂查询走 SQLite，ChromaDB 只做向量检索 |
| 3 | **链存完整快照**: 追踪 chain 里存 `{title, source, published_at}` 自包含，不依赖 news_memory 是否过期 | 数据膨胀 + 快照可能陈旧 | 单用户预计 < 200KB，旧信息可接受 |
| 4 | **按意图分流检索**: 高置信度意图 (>0.85) 只搜相关轨道，低置信度全搜 | 误分漏搜 | 仅高置信度分流，~70% 消息提速，30% 全搜兜底 |
| 5 | **共享基类减重复**: `ChromaRepository` 封装 `upsert` + `rawSearch`，三个 Store 只写业务逻辑 | 基类可能膨胀成万能接口 | 基类只管最基本的两个操作，排序/过滤/聚合由子类实现 |

**文件**: `bot-server/src/main/java/com/bot/memory/` (新包), `ml-server/storage/memory_store.py`

---

### 6. 跨轮次上下文压缩

**为什么做**: 当前 FIFO 裁剪。对话超过 12 条就丢最早的。应该保留重要信息，压缩次要信息。

**要做的事**:

- [ ] 每轮对话结束时，用 LLM 生成一句话摘要
- [ ] 上下文 = 最近 6 条原始消息 + 历史摘要 (最多 500 字)
- [ ] 摘要包含: 用户问了什么、系统做了什么、关键发现

**文件**: `bot-server/src/main/java/com/bot/service/ContextCompressor.java`

---

## 长期规划 (暂停, 设计保留供参考)

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
