import unittest
from unittest.mock import patch

import requests

from services import chat, commentary


class AiErrorHandlingTest(unittest.TestCase):

    @patch.object(chat, "AI_PROVIDER", "openai")
    @patch.object(chat, "_openai_compatible", side_effect=requests.Timeout())
    @patch.object(chat, "get_ai_config", return_value={"api_key": "k", "model": "m", "base_url": "http://example.com"})
    def test_chat_returns_stable_timeout_message(self, _config, _openai):
        reply = chat.generate_reply("hi")

        self.assertEqual("（AI 对话失败：上游服务超时）", reply)

    @patch.object(commentary, "AI_PROVIDER", "openai")
    @patch.object(commentary, "_openai_compatible", side_effect=requests.RequestException())
    @patch.object(commentary, "get_ai_config", return_value={"api_key": "k", "model": "m", "base_url": "http://example.com"})
    def test_commentary_returns_stable_request_failure_message(self, _config, _openai):
        reply = commentary.generate_commentary("content")

        self.assertEqual("（AI 点评失败：上游服务不可用）", reply)


if __name__ == "__main__":
    unittest.main()