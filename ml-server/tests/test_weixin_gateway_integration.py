import json
import tempfile
import threading
import unittest
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock

from weixin_gateway.config import WeixinGatewayConfig
from weixin_gateway.gateway import WeixinGatewayBot
from weixin_gateway.types import LoginCredentials


@dataclass
class AssistantRequestRecorder:
    path: str | None = None
    payload: dict | None = None


def build_handler(recorder: AssistantRequestRecorder):
    class AssistantHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            content_length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(content_length).decode("utf-8")
            recorder.path = self.path
            recorder.payload = json.loads(raw_body)

            response_body = json.dumps(
                {
                    "reply": "热点摘要",
                    "mediaType": "IMAGE",
                    "mediaUrl": "http://127.0.0.1:9000/api/media/cards/news-card.png",
                    "mediaPath": "C:/temp/news-card.png",
                    "mediaCaption": "今日热点卡片",
                }
            ).encode("utf-8")

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)

        def log_message(self, format, *args):
            return

    return AssistantHandler


class WeixinGatewayIntegrationTest(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)

        self.recorder = AssistantRequestRecorder()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), build_handler(self.recorder))
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        self.addCleanup(self._shutdown_server)

        port = self.server.server_address[1]
        self.config = WeixinGatewayConfig(
            ilink_base_url="https://ilinkai.weixin.qq.com",
            cdn_base_url="https://novac2c.cdn.weixin.qq.com/c2c",
            ilink_app_id="bot",
            bot_type="3",
            channel_version="2.4.3",
            bot_agent="hotspot-bot/1.0",
            assistant_api_url=f"http://127.0.0.1:{port}/api/assistant/chat",
            state_dir=Path(self.temp_dir.name),
            api_timeout_seconds=15,
            long_poll_timeout_seconds=35,
            qr_poll_timeout_seconds=35,
            max_consecutive_failures=5,
            retry_delay_seconds=2,
            backoff_delay_seconds=30,
        )
        self.bot = WeixinGatewayBot(self.config)
        self.bot.credentials = LoginCredentials(
            token="test-token",
            base_url="https://ilinkai.weixin.qq.com",
            account_id="test-account",
        )
        self.bot.client.send_media_message = Mock()
        self.bot.client.send_text_message = Mock()

    def _shutdown_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join(timeout=5)

    def test_handle_message_calls_assistant_chat_and_sends_image_reply(self):
        msg = {
            "message_type": 1,
            "from_user_id": "wx-user-1",
            "context_token": "ctx-123",
            "message_id": 101,
            "item_list": [
                {
                    "type": 1,
                    "text_item": {"text": "今日热点"},
                }
            ],
        }

        self.bot.handle_message(msg)

        self.assertEqual("/api/assistant/chat", self.recorder.path)
        self.assertEqual(
            {
                "conversationId": "wechat:wx-user-1",
                "scene": "c2c",
                "senderId": "wx-user-1",
                "chatId": "wx-user-1",
                "msgId": "101",
                "content": "今日热点",
                "sendReply": False,
            },
            self.recorder.payload,
        )
        self.bot.client.send_media_message.assert_called_once_with(
            token="test-token",
            to_user_id="wx-user-1",
            media_source="http://127.0.0.1:9000/api/media/cards/news-card.png",
            media_type="image",
            text="今日热点卡片",
            context_token="ctx-123",
            base_url="https://ilinkai.weixin.qq.com",
            cdn_base_url="https://novac2c.cdn.weixin.qq.com/c2c",
        )
        self.bot.client.send_text_message.assert_not_called()


if __name__ == "__main__":
    unittest.main()