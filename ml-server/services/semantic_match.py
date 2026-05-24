import logging
import math
from typing import Any

from sentence_transformers import CrossEncoder

from config import RERANK_MODEL_NAME
from services.embed import encode


logger = logging.getLogger(__name__)

_reranker = None
_reranker_failed = False


def get_reranker():
    global _reranker
    global _reranker_failed
    if _reranker_failed:
        return None
    if _reranker is None:
        try:
            _reranker = CrossEncoder(RERANK_MODEL_NAME, trust_remote_code=True)
        except Exception:
            logger.exception("Failed to load reranker model=%s", RERANK_MODEL_NAME)
            _reranker_failed = True
            return None
    return _reranker


def rank_candidates(query: str, candidates: list[str], top_k: int | None = None) -> list[dict[str, Any]]:
    normalized_query = (query or "").strip()
    normalized_candidates = [(candidate or "").strip() for candidate in candidates or []]
    if not normalized_query or not normalized_candidates:
        return []

    query_vector = encode([normalized_query])[0]
    candidate_vectors = encode(normalized_candidates)
    scored = []
    for index, (candidate, vector) in enumerate(zip(normalized_candidates, candidate_vectors)):
        scored.append({
            "index": index,
            "candidate": candidate,
            "embed_score": cosine_similarity(query_vector, vector),
        })

    scored.sort(key=lambda item: item["embed_score"], reverse=True)
    narrowed = scored[:max(1, min(top_k or len(scored), len(scored)))]

    reranker = get_reranker()
    if reranker is not None and narrowed:
        try:
            pairs = [(normalized_query, item["candidate"]) for item in narrowed]
            rerank_scores = reranker.predict(pairs)
            for item, rerank_score in zip(narrowed, rerank_scores):
                raw_score = float(rerank_score)
                item["raw_rerank_score"] = raw_score
                item["rerank_score"] = sigmoid(raw_score)
        except Exception:
            logger.exception("Failed to rerank candidates model=%s", RERANK_MODEL_NAME)

    for item in scored:
        item.setdefault("rerank_score", item["embed_score"])
        item["score"] = item["rerank_score"] * 0.75 + item["embed_score"] * 0.25

    scored.sort(key=lambda item: (item["score"], item["embed_score"]), reverse=True)
    return scored


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    return float(sum(a * b for a, b in zip(left, right)))


def sigmoid(value: float) -> float:
    if value >= 0:
        exponent = math.exp(-value)
        return 1.0 / (1.0 + exponent)
    exponent = math.exp(value)
    return exponent / (1.0 + exponent)