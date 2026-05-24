import unittest
from unittest.mock import Mock, patch

import requests

from services import providers
from services.providers import LlmRequestError, LlmSettings, LlmTimeoutError


class ProviderServiceTest(unittest.TestCase):

    @patch.object(providers, "AI_PROVIDER", "openai")
    @patch.object(providers, "get_ai_runtime_config", return_value={
        "timeout_seconds": 15,
        "chat_max_tokens": 800,
        "chat_temperature": 0.6,
        "single_turn_max_tokens": 200,
        "single_turn_temperature": 0.7,
    })
    @patch.object(providers, "get_ai_config", return_value={"api_key": "k", "model": "m", "base_url": "http://example.com"})
    @patch.object(providers, "_call_openai_compatible", return_value="ok")
    def test_call_chat_dispatches_to_openai_compatible(self, mock_openai, _config, _runtime):
        reply = providers.call_chat(
            system_prompt="sys",
            user_message="hello",
            history=[{"role": "assistant", "content": "prev"}],
            max_tokens=123,
            temperature=0.2,
        )

        self.assertEqual("ok", reply)
        mock_openai.assert_called_once_with(
            LlmSettings(provider="openai", api_key="k", model="m", base_url="http://example.com"),
            "sys",
            "hello",
            [{"role": "assistant", "content": "prev"}],
            123,
            0.2,
            15,
        )

    @patch.object(providers, "call_chat", return_value="single")
    @patch.object(providers, "get_ai_runtime_config", return_value={
        "timeout_seconds": 18,
        "chat_max_tokens": 800,
        "chat_temperature": 0.6,
        "single_turn_max_tokens": 222,
        "single_turn_temperature": 0.4,
    })
    def test_call_single_turn_uses_configured_defaults(self, _runtime, mock_call_chat):
        reply = providers.call_single_turn("sys", "prompt")

        self.assertEqual("single", reply)
        mock_call_chat.assert_called_once_with(
            system_prompt="sys",
            user_message="prompt",
            history=None,
            max_tokens=222,
            temperature=0.4,
        )

    @patch.object(providers, "AI_PROVIDER", "openai")
    @patch.object(providers, "get_ai_runtime_config", return_value={
        "timeout_seconds": 12,
        "chat_max_tokens": 321,
        "chat_temperature": 0.45,
        "single_turn_max_tokens": 200,
        "single_turn_temperature": 0.7,
    })
    @patch.object(providers, "get_ai_config", return_value={"api_key": "k", "model": "m", "base_url": "http://example.com"})
    @patch.object(providers, "_call_openai_compatible", return_value="ok")
    def test_call_chat_uses_configured_generation_defaults(self, mock_openai, _config, _runtime):
        reply = providers.call_chat("sys", "hello")

        self.assertEqual("ok", reply)
        mock_openai.assert_called_once_with(
            LlmSettings(provider="openai", api_key="k", model="m", base_url="http://example.com"),
            "sys",
            "hello",
            [],
            321,
            0.45,
            12,
        )

    @patch.object(providers, "AI_PROVIDER", "openai")
    @patch.object(providers, "get_ai_runtime_config", return_value={
        "timeout_seconds": 30,
        "chat_max_tokens": 800,
        "chat_temperature": 0.6,
        "single_turn_max_tokens": 200,
        "single_turn_temperature": 0.7,
    })
    @patch.object(providers, "get_ai_config", return_value={"api_key": "k", "model": "m", "base_url": "http://example.com"})
    @patch.object(providers, "_call_openai_compatible", side_effect=requests.Timeout("timeout"))
    def test_call_chat_wraps_timeout_error(self, _openai, _config, _runtime):
        with self.assertRaises(LlmTimeoutError):
            providers.call_chat("sys", "hello")

    @patch.object(providers, "AI_PROVIDER", "openai")
    @patch.object(providers, "get_ai_runtime_config", return_value={
        "timeout_seconds": 30,
        "chat_max_tokens": 800,
        "chat_temperature": 0.6,
        "single_turn_max_tokens": 200,
        "single_turn_temperature": 0.7,
    })
    @patch.object(providers, "get_ai_config", return_value={"api_key": "k", "model": "m", "base_url": "http://example.com"})
    @patch.object(providers, "_call_openai_compatible", side_effect=requests.RequestException("boom"))
    def test_call_chat_wraps_request_error(self, _openai, _config, _runtime):
        with self.assertRaises(LlmRequestError):
            providers.call_chat("sys", "hello")

    @patch.object(providers.requests, "post")
    def test_openai_compatible_uses_supplied_timeout(self, mock_post):
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {"choices": [{"message": {"content": " ok "}}]}
        mock_post.return_value = response

        reply = providers._call_openai_compatible(
            LlmSettings(provider="openai", api_key="k", model="m", base_url="http://example.com"),
            "sys",
            "hello",
            [],
            111,
            0.5,
            9,
        )

        self.assertEqual("ok", reply)
        self.assertEqual(9, mock_post.call_args.kwargs["timeout"])


if __name__ == "__main__":
    unittest.main()