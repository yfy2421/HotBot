import logging

import requests

from config import AI_PROVIDER, get_ai_config


logger = logging.getLogger(__name__)


DEFAULT_SYSTEM_PROMPT = """你是一个热点追踪分析助手。
你的职责：
1. 优先根据用户问题给出直接答案，不说套话。
2. 如果问题和新闻、舆情、趋势有关，优先给出判断、影响和后续观察点。
3. 如果上下文不足，明确说明缺什么，不要编造。
4. 回答尽量简洁，必要时用 1-3 条短点列出重点。"""


def generate_reply(user_message: str, history: list[dict] | None = None, system_prompt: str | None = None) -> str:
    cfg = get_ai_config()
    api_key = cfg["api_key"]
    model = cfg["model"]

    if not api_key:
        return "（未配置 AI_API_KEY）"

    normalized_history = _normalize_history(history)
    final_system_prompt = system_prompt or DEFAULT_SYSTEM_PROMPT

    try:
        if AI_PROVIDER == "gemini":
            return _gemini(api_key, model, final_system_prompt, user_message, normalized_history)
        if AI_PROVIDER == "claude":
            return _claude(api_key, model, final_system_prompt, user_message, normalized_history)
        return _openai_compatible(cfg, final_system_prompt, user_message, normalized_history)
    except requests.Timeout:
        logger.exception("AI chat timed out provider=%s model=%s", AI_PROVIDER, model)
        return "（AI 对话失败：上游服务超时）"
    except requests.RequestException:
        logger.exception("AI chat request failed provider=%s model=%s", AI_PROVIDER, model)
        return "（AI 对话失败：上游服务不可用）"
    except Exception:
        logger.exception("AI chat failed provider=%s model=%s", AI_PROVIDER, model)
        return "（AI 对话失败：服务内部异常）"


def _normalize_history(history: list[dict] | None) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    for item in history or []:
        role = str(item.get("role", "")).strip().lower()
        content = str(item.get("content", "")).strip()
        if role not in {"user", "assistant"} or not content:
            continue
        result.append({"role": role, "content": content})
    return result[-12:]


def _openai_compatible(cfg: dict, system_prompt: str, user_message: str, history: list[dict[str, str]]) -> str:
    messages = [{"role": "system", "content": system_prompt}, *history, {"role": "user", "content": user_message}]
    resp = requests.post(
        f"{cfg['base_url']}/chat/completions",
        headers={"Authorization": f"Bearer {cfg['api_key']}", "Content-Type": "application/json"},
        json={
            "model": cfg["model"],
            "messages": messages,
            "max_tokens": 800,
            "temperature": 0.6,
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"].strip()


def _gemini(api_key: str, model: str, system_prompt: str, user_message: str, history: list[dict[str, str]]) -> str:
    contents = []
    for item in history:
        role = "model" if item["role"] == "assistant" else "user"
        contents.append({"role": role, "parts": [{"text": item["content"]}]})
    contents.append({"role": "user", "parts": [{"text": user_message}]})

    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    resp = requests.post(
        url,
        json={
            "system_instruction": {"parts": [{"text": system_prompt}]},
            "contents": contents,
            "generationConfig": {"maxOutputTokens": 800, "temperature": 0.6},
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()


def _claude(api_key: str, model: str, system_prompt: str, user_message: str, history: list[dict[str, str]]) -> str:
    from anthropic import Anthropic

    client = Anthropic(api_key=api_key)
    messages = [*history, {"role": "user", "content": user_message}]
    resp = client.messages.create(
        model=model,
        max_tokens=800,
        system=system_prompt,
        messages=messages,
    )
    return resp.content[0].text.strip()