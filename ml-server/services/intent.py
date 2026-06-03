import logging

from services.embed import encode
from services.semantic_match import cosine_similarity


logger = logging.getLogger(__name__)

# ---- Intent taxonomy ----
#
# clear           清空/重置对话上下文
# help            帮助/功能说明
# weather         天气查询
# news_overview   新闻总览/今日热点
# hotlist         热搜/热榜（显式热搜词）
# detail_followup 对当前聚焦条目的追问（分析/详细/原文/正文）
# follow_up       后续追踪/进展跟进
# casual_chat     闲聊/问候/情绪化短句
# default         兜底

# ---- Prototypes (9 intents, ~15 examples each) ----
# Seeds hand-written, then LLM-expanded for coverage across:
#  - formal vs casual register
#  - different sentence patterns (declarative, interrogative, fragment)
#  - edge expressions that keyword matching would miss

PROTOTYPES: dict[str, list[str]] = {
    "clear": [
        "清空对话",
        "清除上下文",
        "重来",
        "全部删掉",
        "忘掉之前说的",
        "从头开始",
        "重置会话",
        "把聊天记录清了",
        "别记着前面的了",
        "之前的都忘掉",
        "重新开始对话",
        "清除所有记录",
        "把刚才说的都删了",
    ],

    "help": [
        "帮助",
        "怎么用",
        "有什么功能",
        "你能做什么",
        "怎么操作",
        "使用说明",
        "功能介绍",
        "能干啥",
        "教教我怎么用",
        "help",
        "/?",
        "/help",
        "使用帮助",
        "可以怎么玩",
        "支持什么命令",
    ],

    "weather": [
        "天气",
        "今天冷不冷",
        "会下雨吗",
        "温度多少",
        "空气质量",
        "今天热不热",
        "风大不大",
        "出门穿什么",
        "要不要带伞",
        "今天多少度",
        "最近天气怎么样",
        "外面冷吗",
        "明天天气",
        "气温多少",
        "下雨了吗",
    ],

    "news_overview": [
        "今日热点",
        "今日新闻",
        "最近有什么新闻",
        "来点新闻",
        "有啥新鲜事",
        "今天发生了什么",
        "最新的消息",
        "看看新闻",
        "有什么好玩的新闻吗",
        "这几天怎么了",
        "有什么大事",
        "看新闻",
        "最近怎么样了",
        "最近闹啥了",
        "播报一下",
        "今天咋样",
        "新闻来一份",
        "最近有啥消息",
        "有什么值得关注的",
        "新鲜事",
    ],

    "hotlist": [
        "热搜",
        "热搜榜",
        "热榜",
        "现在什么最火",
        "微博热搜",
        "实时热搜",
        "有哪些热搜",
        "现在大家在讨论什么",
        "今天热搜",
        "热门话题",
        "热度榜",
        "有什么热搜",
        "看看热搜",
        "热搜是什么",
        "热搜排行",
    ],

    "detail_followup": [
        "分析一下",
        "详细说说",
        "看原文",
        "正文",
        "展开讲讲",
        "能详细说下这条吗",
        "这个具体怎么回事",
        "仔细说说",
        "深度解读",
        "科普一下这条",
        "看不明白，讲详细点",
        "原文呢",
        "全文",
        "说详细点",
        "展开分析",
        "这条具体讲什么",
        "能多讲一点吗",
        "详细点",
        "深入分析",
        "完整内容",
    ],

    "follow_up": [
        "后续",
        "后面怎么样",
        "有进展吗",
        "然后呢",
        "接着说",
        "后来发生了什么",
        "之后怎么样了",
        "有后续吗",
        "接下来怎么样了",
        "还有什么",
        "还有吗",
        "那之后呢",
        "这个有后续吗",
        "跟进了吗",
        "看看后续",
        "还有什么新消息",
        "后面有更新吗",
        "接下来呢",
    ],

    "casual_chat": [
        "早上好",
        "晚安",
        "你好",
        "哈哈",
        "不错",
        "嗯",
        "哦",
        "好吧",
        "谢谢",
        "辛苦啦",
        "在吗",
        "你是谁",
        "今天心情好",
        "牛逼",
        "可以可以",
        "嘿嘿",
        "有意思",
        "好的",
        "明白了",
        "ok",
        "收到",
        "多谢",
        "走了",
    ],

    "default": [
    ],
}

_prototype_vectors: dict[str, list[list[float]]] = {}
_prototype_vectors_loaded = False


def precompute_prototype_embeddings() -> None:
    """Compute and cache prototype embeddings at startup.

    Must be called once before `classify_intent()`.
    All prototypes are short strings (< 50 chars), so encode() is near-instant
    even with ~120 prototypes total.
    """
    global _prototype_vectors
    global _prototype_vectors_loaded
    if _prototype_vectors_loaded:
        return
    total = sum(len(protos) for protos in PROTOTYPES.values() if protos)
    # Flatten all prototype texts into a single list for batch encoding.
    labels: list[str] = []
    texts: list[str] = []
    for label, protos in PROTOTYPES.items():
        for proto in protos:
            labels.append(label)
            texts.append(proto)
    if not texts:
        _prototype_vectors_loaded = True
        return
    vectors = encode(texts)
    for label, vec in zip(labels, vectors):
        _prototype_vectors.setdefault(label, []).append(vec)
    _prototype_vectors_loaded = True
    logger.info("Intent prototypes precomputed: %d texts across %d intents", total, len(PROTOTYPES))


def classify_intent(text: str, threshold: float = 0.65) -> dict:
    """Classify a user message into one of the 9 intent labels.

    Args:
        text: Raw user message.
        threshold: Minimum cosine similarity for a non-default intent.
                   Lower values mean more aggressive classification.

    Returns:
        {"intent": str, "confidence": float, "all_scores": {...}}
        Intent is "default" when confidence < threshold.
    """
    if not _prototype_vectors_loaded:
        precompute_prototype_embeddings()
    normalized = (text or "").strip()
    if not normalized:
        return {"intent": "default", "confidence": 0.0, "all_scores": {}}
    vec = encode([normalized])[0]
    scores: dict[str, float] = {}
    all_labels = set(_prototype_vectors.keys()) | {"default"}
    for label in all_labels:
        proto_vecs = _prototype_vectors.get(label, [])
        if label == "default" or not proto_vecs:
            scores[label] = 0.0
            continue
        scores[label] = max(cosine_similarity(vec, pv) for pv in proto_vecs)
    best_label = max(scores, key=scores.get)  # type: ignore[arg-type]
    best_score = scores[best_label]
    return {
        "intent": best_label if best_score >= threshold else "default",
        "confidence": round(float(best_score), 4),
        "all_scores": {k: round(float(v), 4) for k, v in scores.items()},
    }
