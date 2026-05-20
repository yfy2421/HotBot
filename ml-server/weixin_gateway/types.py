from __future__ import annotations

from dataclasses import dataclass
from typing import Any, TypedDict


class QRCodeResponse(TypedDict, total=False):
    qrcode: str
    qrcode_img_content: str


class QRStatusResponse(TypedDict, total=False):
    status: str
    bot_token: str
    ilink_bot_id: str
    baseurl: str
    ilink_user_id: str
    redirect_host: str


class TextItem(TypedDict, total=False):
    text: str


class RefMessage(TypedDict, total=False):
    title: str


class MessageItem(TypedDict, total=False):
    type: int
    text_item: TextItem
    ref_msg: RefMessage


class WeixinMessage(TypedDict, total=False):
    seq: int
    message_id: int
    from_user_id: str
    to_user_id: str
    client_id: str
    create_time_ms: int
    message_type: int
    message_state: int
    item_list: list[MessageItem]
    context_token: str


class GetUpdatesResponse(TypedDict, total=False):
    ret: int
    errcode: int
    errmsg: str
    msgs: list[WeixinMessage]
    get_updates_buf: str
    longpolling_timeout_ms: int


@dataclass
class LoginCredentials:
    token: str
    base_url: str
    account_id: str
    user_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "token": self.token,
            "base_url": self.base_url,
            "account_id": self.account_id,
            "user_id": self.user_id,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "LoginCredentials":
        return cls(
            token=str(data["token"]),
            base_url=str(data["base_url"]),
            account_id=str(data["account_id"]),
            user_id=str(data.get("user_id")) if data.get("user_id") else None,
        )
