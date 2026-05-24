import logging
from services.providers import LlmMissingApiKeyError
from services.providers import LlmProviderError
from services.providers import LlmRequestError
from services.providers import LlmTimeoutError
from services.providers import call_chat


logger = logging.getLogger(__name__)


DEFAULT_SYSTEM_PROMPT = """你是一个热点追踪分析助手。
你的职责：
1. 优先根据用户问题给出直接答案，不说套话。
2. 如果问题和新闻、舆情、趋势有关，优先给出判断、影响和后续观察点。
3. 如果上下文不足，明确说明缺什么，不要编造。
4. 回答尽量简洁，必要时用 1-3 条短点列出重点。"""


def generate_reply(user_message: str, history: list[dict] | None = None, system_prompt: str | None = None) -> str:
    normalized_history = _normalize_history(history)
    final_system_prompt = system_prompt or DEFAULT_SYSTEM_PROMPT

    try:
        return call_chat(
            system_prompt=final_system_prompt,
            user_message=user_message,
            history=normalized_history,
        )
    except LlmMissingApiKeyError:
        return "（未配置 AI_API_KEY）"
    except LlmTimeoutError as exc:
        logger.exception("AI chat timed out provider=%s model=%s", exc.provider, exc.model)
        return "（AI 对话失败：上游服务超时）"
    except LlmRequestError as exc:
        logger.exception("AI chat request failed provider=%s model=%s", exc.provider, exc.model)
        return "（AI 对话失败：上游服务不可用）"
    except LlmProviderError as exc:
        logger.exception("AI chat failed provider=%s model=%s", exc.provider, exc.model)
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
