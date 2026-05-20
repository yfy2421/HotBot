import logging

import requests
from config import get_ai_config, AI_PROVIDER


logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是一个科技新闻评论员。用 2 句话评价以下新闻：
第 1 句: 这条新闻对普通人有什么影响
第 2 句: 你的独立判断（可以是质疑/补充/趋势预测）
语气: 直接、不官方"""


def generate_commentary(news_content: str) -> str:
    cfg = get_ai_config()
    api_key = cfg["api_key"]
    model = cfg["model"]

    if not api_key:
        return f"（未配置 AI_API_KEY）"

    try:
        if AI_PROVIDER == "gemini":
            return _gemini(api_key, model, news_content)
        elif AI_PROVIDER == "claude":
            return _claude(api_key, model, news_content)
        else:
            return _openai_compatible(cfg, news_content)
    except requests.Timeout:
        logger.exception("AI commentary timed out provider=%s model=%s", AI_PROVIDER, model)
        return "（AI 点评失败：上游服务超时）"
    except requests.RequestException:
        logger.exception("AI commentary request failed provider=%s model=%s", AI_PROVIDER, model)
        return "（AI 点评失败：上游服务不可用）"
    except Exception:
        logger.exception("AI commentary failed provider=%s model=%s", AI_PROVIDER, model)
        return "（AI 点评失败：服务内部异常）"


def _openai_compatible(cfg: dict, content: str) -> str:
    resp = requests.post(
        f"{cfg['base_url']}/chat/completions",
        headers={"Authorization": f"Bearer {cfg['api_key']}", "Content-Type": "application/json"},
        json={
            "model": cfg["model"],
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"新闻内容：\n{content}"},
            ],
            "max_tokens": 200,
            "temperature": 0.7,
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"].strip()


def _gemini(api_key: str, model: str, content: str) -> str:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    resp = requests.post(url, json={
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": [{"role": "user", "parts": [{"text": f"新闻内容：\n{content}"}]}],
        "generationConfig": {"maxOutputTokens": 200, "temperature": 0.7},
    }, timeout=30)
    resp.raise_for_status()
    return resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()


def _claude(api_key: str, model: str, content: str) -> str:
    from anthropic import Anthropic
    client = Anthropic(api_key=api_key)
    resp = client.messages.create(
        model=model, max_tokens=200, system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": f"新闻内容：\n{content}"}],
    )
    return resp.content[0].text.strip()
