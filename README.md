# HotBot

一个面向热点追踪、新闻分析和微信私聊交互的多服务 Bot 原型。

当前仓库包含三条主线能力：

- 日推链路：天气、热点、评论情绪、短期追踪、告警摘要
- 聊天链路：新闻问答、天气问答、热点追踪、多轮上下文
- 微信链路：iLink 扫码登录、长轮询私聊、文本与图片消息回传

## 项目概览

这个项目是一个 Java + Python 的协作系统：

- bot-server：Spring Boot 服务，负责聊天编排、新闻抓取、天气查询、短期追踪、日推调度、新闻卡片 PNG 渲染
- ml-server：FastAPI 服务，负责向量化、点评、情绪分析、NER、Chroma 检索
- weixin-gateway：Python 微信网关，负责接入腾讯 iLink 协议，把微信消息转发给 bot-server，并把文本或图片回复回微信

默认端口：

- bot-server：8080
- ml-server：5000

核心聊天接口：

- POST /api/assistant/chat

## 当前能力

- 新闻源分层：区分 news 和 hotlist 层，并带 sourceType、category、trustLevel
- 新闻抓取优化：并发抓源、单源超时、缓存、告警摘要
- 聊天新闻问答：可基于当前新闻快照回答“最近有什么新闻”“分析 AI 芯片”等问题
- 聊天后续追踪：支持“这些新闻有没有后续”这类近 7 天跟进问题
- 天气硬路由：天气问题直接走 WeatherService，不依赖模型自由生成
- 日推链路：天气、新闻、历史上的今天、评论情绪、短期追踪、数据告警
- 微信私聊接入：扫码登录 iLink、持久化 context_token、文本消息收发、图片消息回传
- 新闻卡片渲染：热点/新闻快照可生成 PNG 卡片，并通过微信发送

## 目录结构

```text
.
├─ bot-server/                  Spring Boot 服务
│  ├─ src/main/java/com/bot/
│  │  ├─ controller/            HTTP 接口
│  │  ├─ service/               核心业务逻辑
│  │  ├─ model/                 请求、响应与领域模型
│  │  └─ scheduler/             日推调度
│  └─ src/test/java/com/bot/    Java 单元测试
├─ ml-server/                   FastAPI 服务 + 微信网关
│  ├─ services/                 AI、点评、情绪、NER
│  ├─ storage/                  Chroma 封装
│  ├─ weixin_gateway/           Python 版 iLink 网关
│  └─ tests/                    Python 单元测试
├─ .env.example                 环境变量示例
└─ start-all.ps1                一键启动脚本
```

## 技术栈

- Java 17
- Spring Boot 3.3.5
- FastAPI 0.115.6
- ChromaDB 0.5.23
- sentence-transformers 3.3.1
- spaCy 3.8.3
- SnowNLP 0.12.3
- pycryptodome 3.21.0

## 环境要求

建议环境：

- Windows 10/11
- Java 17
- Maven 3.9+
- Python 3.10+
- PowerShell 7

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

### 4. 新闻卡片输出目录

```env
BOT_NEWS_CARD_OUTPUT_DIR=
```

留空时默认写到系统临时目录。

完整配置项见 [.env.example](.env.example)。

## 安装依赖

### bot-server 依赖

```powershell
Set-Location .\bot-server
mvn -q -DskipTests package
```

### ml-server 依赖

```powershell
Set-Location .\ml-server
python -m pip install -r requirements.txt
```

首次运行如果使用 spaCy 相关能力，可能还需要额外准备模型环境。

## 启动方式

### 方式一：一键启动

根目录已经提供 [start-all.ps1](start-all.ps1)：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1
```

脚本会：

- 启动 ml-server
- 启动 bot-server
- 启动微信网关
- 等待 5000 和 8080 端口就绪

注意：

- 如果端口已经监听，脚本会跳过启动，不会自动重启旧进程
- 如果你改了代码但服务已在运行，需要先手动停止旧进程再重新执行脚本

### 方式二：分别启动

#### 1. 启动 ml-server

```powershell
Set-Location .\ml-server
uvicorn main:app --host 0.0.0.0 --port 5000
```

#### 2. 启动 bot-server

```powershell
Set-Location .\bot-server
mvn -DskipTests package
java -jar .\target\hotspot-bot-1.0.0.jar
```

#### 3. 启动微信网关

```powershell
Set-Location .\ml-server
python run_weixin_gateway.py
```

首次启动微信网关会在终端输出二维码链接，扫码后即可开始私聊收发。

如果你需要清空微信登录态和上下文缓存：

```powershell
Set-Location .\ml-server
python run_weixin_gateway.py --logout
```

## 主要接口

### bot-server 接口

#### POST /api/assistant/chat

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
- mediaPath：本地生成的 PNG 文件路径
- mediaCaption：可选图片说明

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

典型聊天指令：

- 今日热点
- 最近有什么新闻
- 分析 AI 芯片
- 这些新闻有没有后续
- 今天天气怎么样
- 清空上下文

微信链路下，像“今日热点”这类请求会生成文本摘要 + 新闻卡片 PNG，并通过网关回传到微信。

## 测试

### Java

```powershell
Set-Location .\bot-server
mvn test
```

### Python

```powershell
Set-Location .\ml-server
python -m unittest discover -s tests -p "test_*.py"
```

当前仓库已经包含：

- bot-server 的服务层单测
- ml-server 的 AI 错误处理、Chroma days 过滤、微信媒体消息结构测试

## 当前限制

- 目前更偏向 Windows 本地运行环境
- 微信链路以私聊为主，群聊未完整支持
- 图片回传依赖 bot-server 和微信网关在同一台机器上共享本地文件路径
- start-all.ps1 不会自动重启已存在的旧进程
- 项目已经具备原型系统闭环，但还不是生产级部署形态

## 相关说明

- 微信网关细节见 [ml-server/weixin_gateway/README.md](ml-server/weixin_gateway/README.md)
- 环境变量示例见 [.env.example](.env.example)

## 适合展示的亮点

如果你把这个项目用于简历或面试，建议重点讲这几个点：

- Java 编排层 + Python 能力层的职责拆分
- 新闻源并发抓取、超时控制和缓存策略
- Chroma 短期追踪的查写分离
- 微信 iLink 文本/图片回传链路
- 新闻卡片 PNG 渲染和聊天场景集成
