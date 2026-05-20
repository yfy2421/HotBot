from __future__ import annotations

import json
from pathlib import Path

from .types import LoginCredentials


class WeixinStateStore:
    def __init__(self, state_dir: Path):
        self.state_dir = state_dir
        self.credentials_path = state_dir / "credentials.json"
        self.context_tokens_path = state_dir / "context_tokens.json"
        self.cursor_path = state_dir / "cursor.json"
        self.state_dir.mkdir(parents=True, exist_ok=True)

    def load_credentials(self) -> LoginCredentials | None:
        if not self.credentials_path.exists():
            return None
        data = json.loads(self.credentials_path.read_text(encoding="utf-8"))
        return LoginCredentials.from_dict(data)

    def save_credentials(self, credentials: LoginCredentials) -> None:
        self.credentials_path.write_text(
            json.dumps(credentials.to_dict(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def clear_credentials(self) -> None:
        if self.credentials_path.exists():
            self.credentials_path.unlink()

    def load_context_tokens(self) -> dict[str, str]:
        if not self.context_tokens_path.exists():
            return {}
        data = json.loads(self.context_tokens_path.read_text(encoding="utf-8"))
        return {
            str(key): str(value)
            for key, value in data.items()
            if isinstance(key, str) and isinstance(value, str) and value
        }

    def save_context_tokens(self, tokens: dict[str, str]) -> None:
        self.context_tokens_path.write_text(
            json.dumps(tokens, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def load_cursor(self) -> str:
        if not self.cursor_path.exists():
            return ""
        data = json.loads(self.cursor_path.read_text(encoding="utf-8"))
        cursor = data.get("get_updates_buf", "")
        return str(cursor) if cursor else ""

    def save_cursor(self, cursor: str) -> None:
        self.cursor_path.write_text(
            json.dumps({"get_updates_buf": cursor}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def clear_runtime_state(self) -> None:
        for path in (self.context_tokens_path, self.cursor_path):
            if path.exists():
                path.unlink()
