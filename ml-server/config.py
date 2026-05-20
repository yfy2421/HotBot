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


# ChromaDB
CHROMA_PERSIST_DIR = os.getenv("CHROMA_PERSIST_DIR", os.path.join(os.path.dirname(__file__), "chroma_data"))

# Sentence Transformer
EMBED_MODEL_NAME = os.getenv("EMBED_MODEL_NAME", "paraphrase-multilingual-MiniLM-L12-v2")
