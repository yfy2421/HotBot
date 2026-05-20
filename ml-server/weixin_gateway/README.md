# Weixin Gateway

这个目录是基于 `@tencent-weixin/openclaw-weixin` 协议行为重写的 Python 版微信网关。

设计目标：
- 不依赖 OpenClaw 运行时
- 复用腾讯 iLink 的扫码登录、长轮询和 `sendmessage` 协议
- 将消息转发给当前项目已有的 [bot-server](../../bot-server) 对话接口

当前实现范围：
- 微信私聊文本消息
- 扫码登录与凭证持久化
- `context_token` 持久化
- 文本消息收发

暂未实现：
- 图片、文件、语音、视频上传
- typing 指示
- 群聊

运行前提：
1. 启动 `ml-server`
2. 启动 `bot-server`
3. 运行 `python run_weixin_gateway.py`

首次启动会在终端打印一个微信登录二维码链接，扫码确认后即可开始收发消息。