from __future__ import annotations

from dataclasses import dataclass

import requests

from .config import WeixinGatewayConfig


@dataclass(frozen=True)
class AssistantReply:
    reply: str
    media_type: str | None = None
    media_url: str | None = None
    media_path: str | None = None
    media_caption: str | None = None

    @property
    def has_media(self) -> bool:
        return bool(self.media_type and (self.media_url or self.media_path))


class AssistantBackendClient:
    def __init__(self, config: WeixinGatewayConfig):
        self.config = config
        self.session = requests.Session()

    def chat(self, sender_id: str, content: str, msg_id: str) -> AssistantReply:
        payload = {
            "conversationId": f"wechat:{sender_id}",
            "scene": "c2c",
            "senderId": sender_id,
            "chatId": sender_id,
            "msgId": msg_id,
            "content": content,
            "sendReply": False,
        }
        response = self.session.post(
            self.config.assistant_api_url,
            json=payload,
            timeout=max(self.config.api_timeout_seconds, 30),
        )
        response.raise_for_status()
        data = response.json()
        reply = str(data.get("reply", "")).strip()
        media_type = self._clean_optional_text(data.get("mediaType"))
        media_url = self._clean_optional_text(data.get("mediaUrl"))
        media_path = self._clean_optional_text(data.get("mediaPath"))
        media_caption = self._clean_optional_text(data.get("mediaCaption"))
        has_media_payload = bool(media_type and (media_url or media_path))
        return AssistantReply(
            reply=reply or ("" if has_media_payload else "我这边暂时没生成回复，你可以稍后再试。"),
            media_type=media_type.lower() if media_type else None,
            media_url=media_url,
            media_path=media_path,
            media_caption=media_caption,
        )

    @staticmethod
    def _clean_optional_text(value: object) -> str | None:
        if value is None:
            return None
        text = str(value).strip()
        return text or None
