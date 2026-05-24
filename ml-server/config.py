import os
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))

# AI 通用配置
AI_PROVIDER = os.getenv("AI_PROVIDER", "deepseek").lower()
AI_API_KEY = os.getenv("AI_API_KEY", "")
AI_MODEL = os.getenv("AI_MODEL", "")
AI_BASE_URL = os.getenv("AI_BASE_URL", "")

# 各提供商的默认模型和地址
_PROVIDER_DEFAULTS = {
    "deepseek":  ("deepseek-chat",           "https://api.deepseek.com/v1"),
    "gemini":    ("gemini-2.5-flash",         None),
    "openai":    ("gpt-4o",                   "https://api.openai.com/v1"),
    "kimi":      ("moonshot-v1-8k",           "https://api.moonshot.cn/v1"),
    "grok":      ("grok-3-mini",              "https://api.x.ai/v1"),
    "glm":       ("glm-4-flash",              "https://open.bigmodel.cn/api/paas/v4"),
    "claude":    ("claude-sonnet-4-6",        None),
}


def get_ai_config() -> dict:
    """根据 AI_PROVIDER 返回 {api_key, model, base_url}，用户未填则使用默认值"""
    if AI_PROVIDER not in _PROVIDER_DEFAULTS:
        raise ValueError(f"不支持的 AI_PROVIDER: {AI_PROVIDER}，可选: {', '.join(_PROVIDER_DEFAULTS)}")
    default_model, default_url = _PROVIDER_DEFAULTS[AI_PROVIDER]
    return {
        "api_key":  AI_API_KEY,
        "model":    AI_MODEL or default_model,
        "base_url": AI_BASE_URL or default_url,
    }


def get_ai_runtime_config() -> dict:
    return {
        "timeout_seconds": _read_positive_int_env("AI_REQUEST_TIMEOUT_SECONDS", 30),
        "chat_max_tokens": _read_positive_int_env("AI_CHAT_MAX_TOKENS", 800),
        "chat_temperature": _read_float_env("AI_CHAT_TEMPERATURE", 0.6),
        "single_turn_max_tokens": _read_positive_int_env("AI_SINGLE_TURN_MAX_TOKENS", 200),
        "single_turn_temperature": _read_float_env("AI_SINGLE_TURN_TEMPERATURE", 0.7),
        "commentary_max_tokens": _read_positive_int_env("AI_COMMENTARY_MAX_TOKENS", 500),
    }


def get_cpu_task_config() -> dict:
    return {
        "semantic_executor_workers": _read_positive_int_env("ML_SEMANTIC_EXECUTOR_WORKERS", 1),
        "translation_executor_workers": _read_positive_int_env("ML_TRANSLATION_EXECUTOR_WORKERS", 1),
    }


def get_embed_cache_config() -> dict:
    return {
        "enabled": _read_bool_env("EMBED_CACHE_ENABLED", True),
        "max_entries": _read_positive_int_env("EMBED_CACHE_MAX_ENTRIES", 512),
        "max_text_length": _read_positive_int_env("EMBED_CACHE_MAX_TEXT_LENGTH", 256),
    }


def get_sentiment_config() -> dict:
    positive_threshold = _read_float_env("SENTIMENT_POSITIVE_THRESHOLD", 0.6)
    negative_threshold = _read_float_env("SENTIMENT_NEGATIVE_THRESHOLD", 0.4)
    if not 0.0 <= negative_threshold < positive_threshold <= 1.0:
        positive_threshold = 0.6
        negative_threshold = 0.4
    return {
        "positive_threshold": positive_threshold,
        "negative_threshold": negative_threshold,
    }


def _read_bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name, "").strip().lower()
    if not value:
        return default
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    return default


def _read_positive_int_env(name: str, default: int) -> int:
    value = os.getenv(name, "").strip()
    if not value:
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return parsed if parsed > 0 else default


def _read_float_env(name: str, default: float) -> float:
    value = os.getenv(name, "").strip()
    if not value:
        return default
    try:
        return float(value)
    except ValueError:
        return default


# ChromaDB
CHROMA_PERSIST_DIR = os.getenv("CHROMA_PERSIST_DIR", os.path.join(os.path.dirname(__file__), "chroma_data"))

# Sentence Transformer
EMBED_MODEL_NAME = os.getenv("EMBED_MODEL_NAME", "paraphrase-multilingual-MiniLM-L12-v2")
RERANK_MODEL_NAME = os.getenv("RERANK_MODEL_NAME", "maidalun1020/bce-reranker-base_v1")
NER_MODEL_NAME = os.getenv("NER_MODEL_NAME", "zh_core_web_sm")

# Local translation
TRANSLATION_MODEL_NAME = os.getenv("TRANSLATION_MODEL_NAME", "facebook/nllb-200-distilled-600M")
TRANSLATION_SOURCE_LANG = os.getenv("TRANSLATION_SOURCE_LANG", "eng_Latn")
TRANSLATION_TARGET_LANG = os.getenv("TRANSLATION_TARGET_LANG", "zho_Hans")
