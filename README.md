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
├─ .gitignore                   Git 忽略规则
├─ start-all.ps1                Windows 一键启动脚本
└─ start-all.sh                 Linux 一键启动脚本
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

### 4. 新闻卡片输出目录

```env
BOT_NEWS_CARD_OUTPUT_DIR=
```

留空时默认写到系统临时目录。

完整配置项见 [.env.example](.env.example)。

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

```bash
# Java
cd bot-server && mvn test

# Python
cd ml-server && python -m unittest discover -s tests -p "test_*.py"
```

当前仓库已经包含：

- bot-server 的服务层单测
- ml-server 的 AI 错误处理、Chroma days 过滤、微信媒体消息结构测试

## 当前限制

- 微信链路以私聊为主，群聊未完整支持
- 图片回传依赖 bot-server 和微信网关在同一台机器上共享本地文件路径
- 一键启动脚本不会自动重启已存在的旧进程（需先手动停止）
- 微信网关不建议设为持久化服务（登录态有过期风险，长轮询可能触发 iLink 限流）
- 项目已经具备原型系统闭环，但还不是生产级部署形态

## 相关说明

- 微信网关细节见 [ml-server/weixin_gateway/README.md](ml-server/weixin_gateway/README.md)
- 环境变量示例见 [.env.example](.env.example)
