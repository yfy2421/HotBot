from __future__ import annotations

from dataclasses import dataclass

import requests

from config import AI_PROVIDER, get_ai_config, get_ai_runtime_config


@dataclass(frozen=True)
class LlmSettings:
    provider: str
    api_key: str
    model: str
    base_url: str | None


@dataclass(frozen=True)
class LlmRuntimeSettings:
    timeout_seconds: int
    chat_max_tokens: int
    chat_temperature: float
    single_turn_max_tokens: int
    single_turn_temperature: float
    commentary_max_tokens: int


class LlmProviderError(RuntimeError):
    def __init__(self, provider: str, model: str, message: str):
        super().__init__(message)
        self.provider = provider
        self.model = model


class LlmConfigurationError(LlmProviderError):
    pass


class LlmMissingApiKeyError(LlmConfigurationError):
    pass


class LlmTimeoutError(LlmProviderError):
    pass


class LlmRequestError(LlmProviderError):
    pass


def call_chat(system_prompt: str,
              user_message: str,
              history: list[dict[str, str]] | None = None,
              max_tokens: int | None = None,
              temperature: float | None = None) -> str:
    settings = _load_settings()
    runtime = _load_runtime_settings()
    if not settings.api_key:
        raise LlmMissingApiKeyError(settings.provider, settings.model, "AI_API_KEY not configured")
    resolved_max_tokens = runtime.chat_max_tokens if max_tokens is None else max_tokens
    resolved_temperature = runtime.chat_temperature if temperature is None else temperature
    try:
        if settings.provider == "gemini":
            return _call_gemini(settings, system_prompt, user_message, history or [], resolved_max_tokens, resolved_temperature, runtime.timeout_seconds)
        if settings.provider == "claude":
            return _call_claude(settings, system_prompt, user_message, history or [], resolved_max_tokens, runtime.timeout_seconds)
        return _call_openai_compatible(
            settings,
            system_prompt,
            user_message,
            history or [],
            resolved_max_tokens,
            resolved_temperature,
            runtime.timeout_seconds,
        )
    except LlmProviderError:
        raise
    except Exception as exc:
        raise _normalize_exception(settings, exc) from exc


def call_single_turn(system_prompt: str,
                     user_message: str,
                     max_tokens: int | None = None,
                     temperature: float | None = None) -> str:
    runtime = _load_runtime_settings()
    return call_chat(
        system_prompt=system_prompt,
        user_message=user_message,
        history=None,
        max_tokens=runtime.single_turn_max_tokens if max_tokens is None else max_tokens,
        temperature=runtime.single_turn_temperature if temperature is None else temperature,
    )


def _load_settings() -> LlmSettings:
    try:
        cfg = get_ai_config()
    except ValueError as exc:
        raise LlmConfigurationError(AI_PROVIDER, "", str(exc)) from exc
    return LlmSettings(
        provider=AI_PROVIDER,
        api_key=cfg.get("api_key", ""),
        model=cfg.get("model", ""),
        base_url=cfg.get("base_url"),
    )


def _load_runtime_settings() -> LlmRuntimeSettings:
    cfg = get_ai_runtime_config()
    return LlmRuntimeSettings(
        timeout_seconds=cfg.get("timeout_seconds", 30),
        chat_max_tokens=cfg.get("chat_max_tokens", 800),
        chat_temperature=cfg.get("chat_temperature", 0.6),
        single_turn_max_tokens=cfg.get("single_turn_max_tokens", 200),
        single_turn_temperature=cfg.get("single_turn_temperature", 0.7),
        commentary_max_tokens=cfg.get("commentary_max_tokens", 500),
    )


def _call_openai_compatible(settings: LlmSettings,
                            system_prompt: str,
                            user_message: str,
                            history: list[dict[str, str]],
                            max_tokens: int,
                            temperature: float,
                            timeout_seconds: int) -> str:
    messages = [{"role": "system", "content": system_prompt}, *history, {"role": "user", "content": user_message}]
    resp = requests.post(
        f"{settings.base_url}/chat/completions",
        headers={"Authorization": f"Bearer {settings.api_key}", "Content-Type": "application/json"},
        json={
            "model": settings.model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
        },
        timeout=timeout_seconds,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"].strip()


def _call_gemini(settings: LlmSettings,
                 system_prompt: str,
                 user_message: str,
                 history: list[dict[str, str]],
                 max_tokens: int,
                 temperature: float,
                 timeout_seconds: int) -> str:
    contents = []
    for item in history:
        role = "model" if item["role"] == "assistant" else "user"
        contents.append({"role": role, "parts": [{"text": item["content"]}]})
    contents.append({"role": "user", "parts": [{"text": user_message}]})

    url = f"https://generativelanguage.googleapis.com/v1beta/models/{settings.model}:generateContent?key={settings.api_key}"
    resp = requests.post(
        url,
        json={
            "system_instruction": {"parts": [{"text": system_prompt}]},
            "contents": contents,
            "generationConfig": {"maxOutputTokens": max_tokens, "temperature": temperature},
        },
        timeout=timeout_seconds,
    )
    resp.raise_for_status()
    return resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()


def _call_claude(settings: LlmSettings,
                 system_prompt: str,
                 user_message: str,
                 history: list[dict[str, str]],
                 max_tokens: int,
                 timeout_seconds: int) -> str:
    from anthropic import Anthropic

    client = Anthropic(api_key=settings.api_key, timeout=timeout_seconds)
    messages = [*history, {"role": "user", "content": user_message}]
    resp = client.messages.create(
        model=settings.model,
        max_tokens=max_tokens,
        system=system_prompt,
        messages=messages,
    )
    return resp.content[0].text.strip()


def _normalize_exception(settings: LlmSettings, exc: Exception) -> LlmProviderError:
    message = str(exc) or "provider call failed"
    if isinstance(exc, requests.Timeout) or _looks_like_timeout(exc):
        return LlmTimeoutError(settings.provider, settings.model, message)
    if isinstance(exc, requests.RequestException):
        return LlmRequestError(settings.provider, settings.model, message)
    return LlmProviderError(settings.provider, settings.model, message)


def _looks_like_timeout(exc: Exception) -> bool:
    exc_name = exc.__class__.__name__.lower()
    exc_message = str(exc).lower()
    return "timeout" in exc_name or "timed out" in exc_message or "deadline" in exc_message