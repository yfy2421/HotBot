from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


ROOT_DIR = Path(__file__).resolve().parents[2]
load_dotenv(ROOT_DIR / ".env")


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return int(value)
    except ValueError:
        return default


@dataclass(frozen=True)
class WeixinGatewayConfig:
    ilink_base_url: str
    cdn_base_url: str
    ilink_app_id: str
    bot_type: str
    channel_version: str
    bot_agent: str
    assistant_api_url: str
    state_dir: Path
    api_timeout_seconds: int
    long_poll_timeout_seconds: int
    qr_poll_timeout_seconds: int
    max_consecutive_failures: int
    retry_delay_seconds: int
    backoff_delay_seconds: int


def load_config() -> WeixinGatewayConfig:
    state_dir = os.getenv(
        "WEIXIN_STATE_DIR",
        str(ROOT_DIR / "ml-server" / "storage" / "weixin_gateway"),
    )
    return WeixinGatewayConfig(
        ilink_base_url=os.getenv("WEIXIN_ILINK_BASE_URL", "https://ilinkai.weixin.qq.com"),
        cdn_base_url=os.getenv("WEIXIN_CDN_BASE_URL", "https://novac2c.cdn.weixin.qq.com/c2c"),
        ilink_app_id=os.getenv("WEIXIN_ILINK_APP_ID", "bot"),
        bot_type=os.getenv("WEIXIN_BOT_TYPE", "3"),
        channel_version=os.getenv("WEIXIN_CHANNEL_VERSION", "2.4.3"),
        bot_agent=os.getenv("WEIXIN_BOT_AGENT", "hotspot-bot/1.0"),
        assistant_api_url=os.getenv(
            "WEIXIN_ASSISTANT_API_URL",
            "http://localhost:8080/api/assistant/chat",
        ),
        state_dir=Path(state_dir),
        api_timeout_seconds=_env_int("WEIXIN_API_TIMEOUT_SECONDS", 15),
        long_poll_timeout_seconds=_env_int("WEIXIN_LONG_POLL_TIMEOUT_SECONDS", 35),
        qr_poll_timeout_seconds=_env_int("WEIXIN_QR_POLL_TIMEOUT_SECONDS", 35),
        max_consecutive_failures=_env_int("WEIXIN_MAX_CONSECUTIVE_FAILURES", 5),
        retry_delay_seconds=_env_int("WEIXIN_RETRY_DELAY_SECONDS", 2),
        backoff_delay_seconds=_env_int("WEIXIN_BACKOFF_DELAY_SECONDS", 30),
    )
