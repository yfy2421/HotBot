# HotBot

一个面向热点追踪、新闻分析和微信私聊交互的多服务 Bot 原型。

当前仓库包含三条主线能力：

- 日推链路：天气、热点、评论情绪、短期追踪、告警摘要
- 聊天链路：新闻问答、天气问答、热点追踪、多轮上下文
- 微信链路：iLink 扫码登录、长轮询私聊、文本与图片消息回传

## 项目概览

这是一个 Java + Python 的协作系统，三条链路独立运行：

- **bot-server**：Spring Boot 服务，负责聊天编排、意图路由、新闻抓取、天气查询、短期追踪、日推调度、新闻卡片 PNG 渲染。聊天服务层已拆分为多个专职组件：
  - `IntentRouter` / `IntentKeywords`：三层渐进意图路由（L1 关键词 → L2 Embedding 原型 → L3 兜底）
  - `AssistantConversationService`（含 toolLoop）：Tool Calling 编排、ReAct 风格工具循环、多步任务协调
  - `DetailSelector`：三级详情匹配（序号 → 标题/关键词 → 语义相似度）
  - `ConversationStateManager`：多轮对话上下文 + 新闻快照持久化，支持重启后继续追问
  - `NewsSnapshotManager`：快照抓取、分类、总览平衡
  - `ReplyRenderer`：文本回复 + 新闻卡片拼装
  - `NewsCardRenderer`：Java 2D 渲染，生成新闻总览 / 详情卡片 PNG（渐变徽章、斑马条纹、品牌水印）
- **ml-server**：FastAPI 服务，负责 LLM 对话、点评生成、情绪分析、NER 实体抽取、embedding / 语义匹配、翻译 (NLLB)、Chroma 向量检索。LLM 调用已抽象为 `providers.py` 统一适配多厂商（deepseek / openai / gemini / glm 等）
- **weixin-gateway**：Python 微信网关，接入腾讯 iLink 协议（根据腾讯官方提供给openclaw的npm包实现），把微信消息转发给 bot-server，媒体消息优先走 bot-server 返回的 HTTP 媒体地址，必要时回退本地路径

默认端口：

- bot-server：8080
- ml-server：5000

核心聊天接口：

- POST /api/assistant/chat

## 当前能力

### 聊天 Bot

- **意图路由**：3 层渐进分类（L1 关键词 0ms → L2 Embedding 原型 ~15ms → L3 关键词兜底），9 类意图 + 置信度评分
- **Tool Calling**：LLM 可自主调用 3 个工具（fetch_news / analyze_article / track_follow_up），ReAct 风格循环，最多 3 轮
- **置信度反问**：低置信度时不硬猜，根据对话状态给不同的澄清引导
- **新闻总览**：支持”今日热点””最近有什么新闻”等，总览卡带渐变排名徽章（#1 红橙 / #2 金黄 / #3 蓝青）和斑马条纹
- **详情切换**：支持序号、标题片段、完整标题 + “的原文”等多种方式，详情卡保留段落排版 + AI 点评
- **聚焦分析**：切到某条新闻后，支持”分析这条””看原文””正文”等追问
- **后续追踪**：支持”后续””后面怎么样”，基于 Chroma 相似度检索近 7 天相关报道
- **推送去重**：每日推送前 MD5 签名对比，内容无变化则跳过
- **多源回退**：RSS 源支持配置备用 URL 列表，主源失败自动切换
- **天气查询**：天气相关问题硬路由到和风天气 API
- **闲聊处理**：问候、情绪化短句简短自然回应，不做新闻分析
- **会话持久化**：多轮上下文和新闻快照落盘，重启后继续追问
- **命令**：支持 `clear` / `清空上下文`、`help` / `帮助`
- **新闻卡片渲染**：Java 2D 原生 PNG 渲染（渐变徽章 + 斑马条纹 + 品牌水印），零外部依赖

### 日推链路

- 天气、新闻、历史上的今天、评论情绪、短期追踪、数据告警
- 每日定时推送，走 `DailyBotScheduler`

### 微信私聊

- iLink 扫码登录，持久化登录态
- 文本消息收发，图片消息回传（总览卡 + 详情卡）
- 消息格式优先走 bot-server HTTP 媒体地址

## 目录结构

```text
.
├─ bot-server/                       Spring Boot 服务
│  ├─ src/main/java/com/bot/
│  │  ├─ controller/                 HTTP 接口 (chat / media / ready)
│  │  ├─ service/                    核心业务逻辑
│  │  │  ├─ AssistantConversationService  聊天主编排
│  │  │  ├─ IntentRouter / IntentKeywords  意图分类与关键词
│  │  │  ├─ DetailSelector                 三级详情匹配
│  │  │  ├─ ConversationStateManager       会话状态持久化
│  │  │  ├─ NewsSnapshotManager            快照抓取与分类
│  │  │  ├─ ReplyRenderer                  回复组装与卡片拼装
│  │  │  ├─ NewsCardRenderer               新闻卡片 PNG 渲染
│  │  │  ├─ NewsService                    新闻 RSS 抓取
│  │  │  ├─ WeatherService                 和风天气
│  │  │  ├─ TrackingService                短期追踪
│  │  │  └─ CommentSourceService           评论来源
│  │  ├─ model/                    请求、响应与领域模型
│  │  ├─ config/                   配置类 (AppConfig)
│  │  ├─ client/                   PythonMLClient (Resilient 包装)
│  │  ├─ agent/                    Tool Calling 协议 (Tool 接口 + 3 tool)
│  │  └─ scheduler/                日推调度 (DailyBotScheduler)
│  └─ src/test/java/com/bot/     Java 单元测试
├─ ml-server/                    FastAPI 服务 + 微信网关
│  ├─ services/                  AI 服务
│  │  ├─ providers.py            LLM 多厂商统一适配
│  │  ├─ chat.py                 多轮对话
│  │  ├─ commentary.py           AI 点评生成
│  │  ├─ sentiment.py            情绪分析
│  │  ├─ ner.py                  实体抽取
│  │  ├─ embed.py                Embedding (带 LRU 缓存)
│  │  ├─ semantic_match.py       Embedding + CrossEncoder 重排序
│  │  ├─ translation.py          翻译 (NLLB)
│  │  └─ task_dispatch.py        CPU 任务调度
│  ├─ storage/                   Chroma 持久化封装
│  ├─ weixin_gateway/            Python 版 iLink 网关
│  └─ tests/                     Python 单元测试
├─ .github/workflows/            GitHub Actions CI
├─ .env.example                  环境变量示例
├─ .gitignore                    Git 忽略规则
├─ start-all.ps1                 Windows 一键启动脚本
└─ start-all.sh                  Linux 一键启动脚本
```

## 技术栈

- Java 17
- Spring Boot 3.3.5
- FastAPI 0.115.6
- ChromaDB 0.5.23
- sentence-transformers 3.3.1 (Embedding + CrossEncoder 重排序)
- spaCy 3.8.3 (NER 中文实体抽取)
- SnowNLP 0.12.3 (中文情绪分析)
- pycryptodome 3.21.0 (iLink 加密)

## 环境要求

硬件建议：

- 内存：不低于 2 GB 可用（ChromaDB + sentence-transformers 首次加载模型会占额外内存）
- 磁盘：不低于 1 GB 可用（含依赖、Chroma 持久化数据、新闻卡片 PNG 缓存）

支持的操作系统：

- Windows 10/11（PowerShell 7）
- Linux（Ubuntu 20.04+ / Debian 11+ / CentOS 7+，bash 环境）
- macOS 未经完整测试，但 start-all.sh 理论上可运行

公共依赖：

- Java 17+
- Maven 3.9+
- Python 3.10+
- 至少一个可用的大模型 API Key

各平台额外需要的工具：

- Linux：`ss` 或 `netstat` 或 `lsof`（start-all.sh 端口检测用），均为常见预装工具
- Windows：PowerShell 7（推荐）或 Windows PowerShell 5.1

如果你只想跑聊天主链，最低需要：

1. Java 17
2. Python 环境
3. 至少一个可用的大模型 API Key

## 配置说明

先复制环境变量文件：

```powershell
Copy-Item .env.example .env
```

至少需要关注这几类配置：

### 1. AI 模型

```env
AI_PROVIDER=deepseek
AI_API_KEY=
AI_MODEL=
AI_BASE_URL=
```

### 2. 和风天气

如果你需要天气功能，再配置：

```env
HEFENG_API_HOST=
HEFENG_PROJECT_ID=
HEFENG_CREDENTIAL_ID=
HEFENG_AIR_COORDINATES=
HEFENG_PRIVATE_KEY=
```

### 3. 微信 iLink 网关

```env
WEIXIN_ILINK_BASE_URL=https://ilinkai.weixin.qq.com
WEIXIN_CDN_BASE_URL=https://novac2c.cdn.weixin.qq.com/c2c
WEIXIN_ILINK_APP_ID=bot
WEIXIN_BOT_TYPE=3
WEIXIN_CHANNEL_VERSION=2.4.3
WEIXIN_BOT_AGENT=hotspot-bot/1.0
WEIXIN_ASSISTANT_API_URL=http://localhost:8080/api/assistant/chat
WEIXIN_STATE_DIR=./ml-server/storage/weixin_gateway
```

如果微信网关和 bot-server 不在同一台机器，`WEIXIN_ASSISTANT_API_URL` 不能再写 `localhost`，而要改成微信网关实际可访问到的 bot-server 地址，例如 `http://your-bot-server:8080/api/assistant/chat`。

### 4. 新闻卡片输出目录

```env
BOT_NEWS_CARD_OUTPUT_DIR=
```

留空时默认写到系统临时目录。

### 5. Assistant 会话状态目录

```env
BOT_ASSISTANT_STATE_DIR=./bot-server/storage/assistant
```

bot-server 会把多轮聊天上下文和最近一次新闻快照写到这个目录下，默认文件名是 `assistant-state.json`。如果你希望服务重启后还能继续追问，不要把它指到系统临时目录。

### 6. 新闻 RSS 源配置（application.yml）

RSS 源从 `.env` 迁移到了 [application.yml](bot-server/src/main/resources/application.yml) 的原生 YAML 列表，支持按 Name、URL、Type、Category、Trust 逐条配置。当前默认源：

| 来源 | 类型 | 分类 |
| --- | --- | --- |
| 新华社新闻 | RSS (聚合) | general |
| 36氪 | RSS (官方) | tech |
| 环球科学 | RSS (聚合) | tech |
| 环球时报 | RSS (聚合) | general |
| 中国国家地理 | RSS (聚合) | general |
| HackerNews | RSS (聚合) | tech |
| 知乎日报 | API (聚合) | general |

如需增减源，直接编辑 `application.yml` 中 `bot.news.rss-feeds` 列表即可，无需改 `.env`。

### 7. AI 点评字数上限

```env
AI_COMMENTARY_MAX_TOKENS=500
```

详情卡中的 AI 点评使用独立的 token 预算，避免与单页新闻原文共用字数限制导致截断。不设则默认 500。

## 安装依赖

### 1. 准备环境变量

首先复制环境变量模板：

```bash
# Linux / macOS
cp .env.example .env

# Windows PowerShell
Copy-Item .env.example .env
```

编辑 `.env`，填入 AI_API_KEY 和其他必要配置。详见上方配置说明。

### 2. bot-server 依赖

```bash
cd bot-server
mvn -q -DskipTests package
```

### 3. ml-server 依赖

```bash
cd ml-server
python -m pip install -r requirements.txt
```

如果使用 spaCy 中文 NER，首次运行前还需要下载模型：

```bash
python -m spacy download zh_core_web_sm
```

### 4. （Linux 专属）确保 start-all.sh 可执行

```bash
chmod +x start-all.sh
```

## 启动方式

服务器端口默认：bot-server 8080，ml-server 5000。如需修改，在 `.env` 中设置 `BOT_SERVER_PORT` 和 `ML_SERVER_PORT`。

### 方式一：一键启动

#### Windows（[start-all.ps1](start-all.ps1)）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1
```

#### Linux / macOS（[start-all.sh](start-all.sh)）

```bash
./start-all.sh
```

两个脚本功能一致：启动 ml-server → 等待端口就绪 → 启动 bot-server → 等待端口就绪 → 启动微信网关。

可用的附加参数：

```bash
# 预览将要执行的命令（不实际启动）
./start-all.ps1 -DryRun          # Windows
./start-all.sh --dry-run         # Linux

# 查看服务运行状态
./start-all.sh --status

# 停止所有后台服务
./start-all.sh --stop
```

注意：

- 如果端口已经监听，脚本会跳过启动，不会自动重启旧进程
- 如果你改了代码但服务已在运行，需要先手动停止旧进程再重新执行脚本
- Linux 版输出写入 `logs/` 目录；Ctrl+C 可一键停止所有服务

### 方式二：分别启动

#### 1. 启动 ml-server

```bash
# Linux / macOS
cd ml-server && uvicorn main:app --host 0.0.0.0 --port 5000

# Windows PowerShell
Set-Location .\ml-server; uvicorn main:app --host 0.0.0.0 --port 5000
```

#### 2. 启动 bot-server

```bash
# Linux / macOS
cd bot-server && mvn -DskipTests -q package && java -jar target/hotspot-bot-1.0.0.jar

# Windows PowerShell
Set-Location .\bot-server; mvn -DskipTests package; java -jar .\target\hotspot-bot-1.0.0.jar
```

#### 3. 启动微信网关

```bash
# Linux / macOS
cd ml-server && python run_weixin_gateway.py

# Windows PowerShell
Set-Location .\ml-server; python run_weixin_gateway.py
```

首次启动微信网关会在终端输出二维码链接，扫码后即可开始私聊收发。

如果你需要清空微信登录态和上下文缓存：

```bash
cd ml-server && python run_weixin_gateway.py --logout
```

## 持久化运行（Linux）

以下均以 Ubuntu 为例，其他发行版按需调整路径。

### systemd 托管

将服务注册为 systemd 单元，实现开机自启、异常退出自动重启。

#### 1. 创建 ml-server 单元

```bash
sudo tee /etc/systemd/system/hotbot-ml.service <<'EOF'
[Unit]
Description=HotBot ML Server (FastAPI)
After=network.target

[Service]
Type=simple
User=your-user
WorkingDirectory=/home/your-user/HotBot/ml-server
ExecStart=/usr/bin/python -m uvicorn main:app --host 0.0.0.0 --port 5000
EnvironmentFile=/home/your-user/HotBot/.env
Restart=on-failure
RestartSec=5
StandardOutput=append:/home/your-user/HotBot/logs/ml-server.log
StandardError=append:/home/your-user/HotBot/logs/ml-server.log

[Install]
WantedBy=multi-user.target
EOF
```

#### 2. 创建 bot-server 单元

```bash
sudo tee /etc/systemd/system/hotbot-bot.service <<'EOF'
[Unit]
Description=HotBot Bot Server (Spring Boot)
After=network.target hotbot-ml.service
Requires=hotbot-ml.service

[Service]
Type=simple
User=your-user
WorkingDirectory=/home/your-user/HotBot/bot-server
ExecStart=/usr/bin/java -jar /home/your-user/HotBot/bot-server/target/hotspot-bot-1.0.0.jar
EnvironmentFile=/home/your-user/HotBot/.env
Restart=on-failure
RestartSec=10
StandardOutput=append:/home/your-user/HotBot/logs/bot-server.log
StandardError=append:/home/your-user/HotBot/logs/bot-server.log

[Install]
WantedBy=multi-user.target
EOF
```

#### 3. 启用并启动

```bash
sudo systemctl daemon-reload
sudo systemctl enable hotbot-ml hotbot-bot
sudo systemctl start hotbot-ml hotbot-bot
```

#### 4. 日常管理

```bash
sudo systemctl status hotbot-ml hotbot-bot    # 查看状态
sudo systemctl restart hotbot-bot              # 重启 bot-server
sudo journalctl -u hotbot-ml -f                # 实时日志
```

### 进程监控（备选）

如果不希望用 systemd，也可以搭配 `supervisord` 或 `pm2`：

```bash
# pm2 示例
pm2 start "uvicorn main:app --host 0.0.0.0 --port 5000" --name hotbot-ml --cwd ./ml-server
pm2 start "java -jar target/hotspot-bot-1.0.0.jar" --name hotbot-bot --cwd ./bot-server
pm2 save && pm2 startup
```

微信网关目前建议仅在需要扫码或日常私聊收发时手动启动，不推荐设为持久化服务（登录态有过期风险，且长时间轮询可能触发 iLink 限流）。

## 主要接口

### bot-server 接口

#### POST /api/assistant/chat

核心聊天端点，支持所有意图。

请求示例：

```json
{
  "conversationId": "wechat:test-user",
  "scene": "c2c",
  "senderId": "test-user",
  "chatId": "test-user",
  "msgId": "msg-1",
  "content": "今日热点",
  "sendReply": false
}
```

返回中可能包含：

- reply：文本回复
- newsSnapshot：本轮新闻快照
- mediaType：例如 image
- mediaUrl：bot-server 生成的媒体访问地址，微信网关优先使用这个 URL 拉取图片
- mediaPath：本地生成的 PNG 文件路径，作为同机部署时的本地回退
- mediaCaption：可选图片说明

#### GET /api/media/cards/{fileName}

返回 bot-server 生成的新闻卡片 PNG（总览卡 / 详情卡），`POST /api/assistant/chat` 中的 `mediaUrl` 默认指向这个接口。

#### GET /api/ready

就绪检测端点，返回服务启动状态，可用于负载均衡和健康检查。

### ml-server 接口

主要接口：

- POST /api/chat
- POST /api/embed
- POST /api/commentary
- POST /api/sentiment
- POST /api/ner
- POST /api/news/add
- POST /api/news/similar
- POST /api/entity/add
- POST /api/entity/history
- GET /api/health

## 使用示例

### 典型聊天交互

```text
用户: 今日热点
 Bot: 回总览卡 PNG，底部提示”回复 1-12 或标题查看详情”

用户: 3
 Bot: 切到第 3 条新闻的详情卡

用户: AI芯片那条
 Bot: 模糊匹配到某条标题含”芯片”的新闻，切详情卡

用户: 圆桌对话:下一个杀手级AI产品...的原文
 Bot: 识别完整标题 + “的原文”，直接返回该条详情

用户: 分析一下
 Bot: 对当前聚焦条目输出 AI 分析（核心判断 + 为什么重要 + 风险提示）

用户: 看后续
 Bot: 检索近 7 天相似报道，如无直接后续则推荐相关延伸阅读

用户: 最近有什么新闻
 Bot: 拉新快照，生成总览卡

用户: 早上好
 Bot: 简短问候回应，不做新闻分析

用户: 今天天气怎么样
 Bot: 直接查和风天气 API 返回

用户: 清空上下文
 Bot: 清除本轮对话历史
```

### 支持的指令形式

| 类别 | 示例 |
| --- | --- |
| 新闻总览 | “今日热点””最近有什么新闻””来点新闻””有啥新鲜事” |
| 详情切换 | “1””第3条””AI那条””XX标题的原文””看原文” |
| 聚焦分析 | “分析一下””详细说说””讲详细点””深度解读” |
| 后续追踪 | “后续””后面怎么样””有进展吗””然后呢” |
| 天气 | “天气””冷不冷””会下雨吗””空气质量” |
| 闲聊 | “早上好””你好””哈哈””晚安”（简短回应，不分析） |
| 系统 | “清空上下文””clear””帮助””help” |

微信链路下，新闻总览和详情切换会生成 PNG 卡片。bot-server 先暴露 HTTP 媒体地址，微信网关再拉取图片回传到微信。

## 测试

```bash
# Java
cd bot-server && mvn test

# Python 全量
PYTHONPATH=ml-server python -m unittest discover -s ml-server/tests -p "test_*.py" -v

# 微信链路回归
PYTHONPATH=ml-server python -m unittest discover -s ml-server/tests -p "test_weixin*.py" -v
```

如果你在 Windows PowerShell 下从仓库根目录运行 Python 测试，先执行：

```powershell
$env:PYTHONPATH='ml-server'
python -m unittest discover -s ml-server/tests -p "test_*.py" -v
python -m unittest discover -s ml-server/tests -p "test_weixin*.py" -v
```

当前仓库已经包含：

- **121 Java 单元测试**：覆盖意图路由、Tool Calling 解析（19 边界测试）、新闻卡片渲染（9 渲染测试）、会话状态、详情匹配、回复拼装、熔断客户端
- **21 Python 单元测试 + 25 综合测试**：覆盖意图分类、AI provider、语义匹配、NER、翻译、embedding 缓存、CPU 调度、微信网关集成

## CI

仓库已包含 GitHub Actions 工作流 [`.github/workflows/ci.yml`](.github/workflows/ci.yml)，在 push 到 `main` 和 pull request 时自动执行：

- `bot-server` 的 `mvn test`（120+ 测试）
- Python 全量单测 + 微信链路回归

Intent 分类使用预计算原型向量缓存（`prototype_vectors.json`），CI 运行无需下载 Embedding 模型，避免 HF 超时问题。

## 当前限制

- **Memory**：当前仅为 12 条 FIFO 会话历史 + 30 分钟快照缓存，无长期记忆。双轨 Memory（浏览+追踪+社交）设计已完成，待落地
- **L3 意图路由**：L3 目前仍为关键词兜底，尚未升级为 LLM 分类
- **Tool Calling**：协议已实现，但尚未用真实 LLM 验证格式兼容性（全 mock 测试通过）
- **微信链路**：以私聊为主，群聊未完整支持
- **会话持久化**：单机文件存储，多实例需改为共享存储
- **微信网关持久化**：不建议设为持久化服务（登录态过期风险）
- **新闻详情卡**：仅文字 + AI 点评，RSS 图片渲染尚未落地
- 项目已具备原型系统闭环，当前处于**收敛模式**：消化现有功能、对齐文档、补充测试，中长规划暂缓后再评估

## 相关说明

- 微信网关细节见 [ml-server/weixin_gateway/README.md](ml-server/weixin_gateway/README.md)
- 环境变量示例见 [.env.example](.env.example)
- 项目路线图见 [ROADMAP.md](ROADMAP.md)
- 功能质疑与验证见 [质疑与验证.md](质疑与验证.md)
- 演示脚本见 [演示脚本.md](演示脚本.md)
- 对标分析： [astrbot_plugin_dailyhub-master/](astrbot_plugin_dailyhub-master/)（每日资讯插件）
