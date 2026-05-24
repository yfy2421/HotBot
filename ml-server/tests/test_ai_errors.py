import unittest
from unittest.mock import patch

from services import chat, commentary
from services.providers import LlmRequestError, LlmTimeoutError


class AiErrorHandlingTest(unittest.TestCase):

    @patch("services.chat.call_chat", side_effect=LlmTimeoutError("openai", "m", "timeout"))
    def test_chat_returns_stable_timeout_message(self, _call_chat):
        reply = chat.generate_reply("hi")

        self.assertEqual("（AI 对话失败：上游服务超时）", reply)

    @patch("services.commentary.call_single_turn", side_effect=LlmRequestError("openai", "m", "request failed"))
    def test_commentary_returns_stable_request_failure_message(self, _call_single_turn):
        reply = commentary.generate_commentary("content")

        self.assertEqual("（AI 点评失败：上游服务不可用）", reply)


if __name__ == "__main__":
    unittest.main()