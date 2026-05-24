import logging
from services.providers import LlmMissingApiKeyError
from services.providers import LlmProviderError
from services.providers import LlmRequestError
from services.providers import LlmTimeoutError
from services.providers import _load_runtime_settings
from services.providers import call_single_turn


logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是一个科技新闻评论员。用 2 句话评价以下新闻：
第 1 句: 这条新闻对普通人有什么影响
第 2 句: 你的独立判断（可以是质疑/补充/趋势预测）
语气: 直接、不官方"""


def generate_commentary(news_content: str) -> str:
    runtime = _load_runtime_settings()
    try:
        return call_single_turn(
            system_prompt=SYSTEM_PROMPT,
            user_message=f"新闻内容：\n{news_content}",
            max_tokens=runtime.commentary_max_tokens,
        )
    except LlmMissingApiKeyError:
        return "（未配置 AI_API_KEY）"
    except LlmTimeoutError as exc:
        logger.exception("AI commentary timed out provider=%s model=%s", exc.provider, exc.model)
        return "（AI 点评失败：上游服务超时）"
    except LlmRequestError as exc:
        logger.exception("AI commentary request failed provider=%s model=%s", exc.provider, exc.model)
        return "（AI 点评失败：上游服务不可用）"
    except LlmProviderError as exc:
        logger.exception("AI commentary failed provider=%s model=%s", exc.provider, exc.model)
        return "（AI 点评失败：服务内部异常）"
