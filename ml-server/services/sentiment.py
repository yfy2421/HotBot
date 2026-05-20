import re
from snownlp import SnowNLP

# 超过此长度则分句处理，避免长评论情感被稀释
LONG_THRESHOLD = 80

# 中文断句
_SENTENCE_PAT = re.compile(r"[。！？；\n]+")


def _score(text: str) -> float:
    """对单条文本打分，长文本自动分句加权聚合"""
    if len(text) <= LONG_THRESHOLD:
        return SnowNLP(text).sentiments

    # 长文本：按句号/感叹号/问号/换行断开，逐句打分取平均
    sentences = [s.strip() for s in _SENTENCE_PAT.split(text) if len(s.strip()) >= 4]
    if not sentences:
        return SnowNLP(text).sentiments

    scores = [SnowNLP(s).sentiments for s in sentences]
    return sum(scores) / len(scores)


def analyze(comments: list[str]) -> dict:
    """中文情感分析，返回 {positive, negative, neutral, summary}"""
    if not comments:
        return {"positive": 0, "negative": 0, "neutral": 0, "summary": "无评论数据"}

    results = {"positive": 0, "negative": 0, "neutral": 0}

    for text in comments:
        score = _score(text)
        if score > 0.6:
            results["positive"] += 1
        elif score < 0.4:
            results["negative"] += 1
        else:
            results["neutral"] += 1

    total = len(comments)
    pos_pct = results["positive"] / total * 100
    neg_pct = results["negative"] / total * 100

    if pos_pct > 50:
        summary = "网友普遍看好"
    elif neg_pct > 50:
        summary = "网友普遍质疑"
    elif abs(pos_pct - neg_pct) < 15:
        summary = "争议较大，观点分化"
    else:
        summary = "舆论倾向不一"

    results["summary"] = summary
    return results
