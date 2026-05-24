from collections import OrderedDict
import threading

from sentence_transformers import SentenceTransformer

from config import EMBED_MODEL_NAME, get_embed_cache_config

_model = None
_cache_lock = threading.Lock()
_embed_cache: OrderedDict[str, tuple[float, ...]] = OrderedDict()
_cache_metrics = {
    "hits": 0,
    "misses": 0,
    "stores": 0,
    "bypassed": 0,
}


def get_model():
    global _model
    if _model is None:
        _model = SentenceTransformer(EMBED_MODEL_NAME)
    return _model


def embed_cache_metrics() -> dict:
    config = get_embed_cache_config()
    with _cache_lock:
        return {
            "enabled": config["enabled"],
            "size": len(_embed_cache),
            "max_entries": config["max_entries"],
            "max_text_length": config["max_text_length"],
            "hits": _cache_metrics["hits"],
            "misses": _cache_metrics["misses"],
            "stores": _cache_metrics["stores"],
            "bypassed": _cache_metrics["bypassed"],
        }


def clear_embed_cache() -> None:
    with _cache_lock:
        _embed_cache.clear()
        for key in _cache_metrics:
            _cache_metrics[key] = 0


def encode(texts: list[str]) -> list[list[float]]:
    """Encode a list of texts into embedding vectors."""
    if not texts:
        return []
    model = get_model()
    cache_config = get_embed_cache_config()
    if not cache_config["enabled"]:
        _record_bypass(len(texts))
        return _encode_batch(model, texts)

    results: list[list[float] | None] = [None] * len(texts)
    missing_keys: list[str] = []
    missing_positions: dict[str, list[int]] = {}
    uncached_positions: list[int] = []
    uncached_texts: list[str] = []

    for index, text in enumerate(texts):
        if _is_cacheable(text, cache_config["max_text_length"]):
            if text in missing_positions:
                missing_positions[text].append(index)
                continue
            cached_vector = _cache_get(text)
            if cached_vector is not None:
                results[index] = list(cached_vector)
                continue
            missing_keys.append(text)
            missing_positions[text] = [index]
            continue
        uncached_positions.append(index)
        uncached_texts.append(text)

    if uncached_texts:
        _record_bypass(len(uncached_texts))
        uncached_vectors = _encode_batch(model, uncached_texts)
        for index, vector in zip(uncached_positions, uncached_vectors):
            results[index] = vector

    if missing_keys:
        missing_vectors = _encode_batch(model, missing_keys)
        for text, vector in zip(missing_keys, missing_vectors):
            frozen_vector = tuple(vector)
            _cache_put(text, frozen_vector, cache_config["max_entries"])
            for position in missing_positions[text]:
                results[position] = list(frozen_vector)

    return [vector if vector is not None else [] for vector in results]


def _encode_batch(model, texts: list[str]) -> list[list[float]]:
    embeddings = model.encode(texts, normalize_embeddings=True)
    if hasattr(embeddings, "tolist"):
        vectors = embeddings.tolist()
    else:
        vectors = [list(item) for item in embeddings]
    return [[float(value) for value in vector] for vector in vectors]


def _is_cacheable(text: str, max_text_length: int) -> bool:
    return isinstance(text, str) and 0 < len(text) <= max_text_length


def _cache_get(text: str) -> tuple[float, ...] | None:
    with _cache_lock:
        cached = _embed_cache.get(text)
        if cached is None:
            _cache_metrics["misses"] += 1
            return None
        _embed_cache.move_to_end(text)
        _cache_metrics["hits"] += 1
        return cached


def _cache_put(text: str, vector: tuple[float, ...], max_entries: int) -> None:
    with _cache_lock:
        _embed_cache[text] = vector
        _embed_cache.move_to_end(text)
        _cache_metrics["stores"] += 1
        while len(_embed_cache) > max_entries:
            _embed_cache.popitem(last=False)


def _record_bypass(count: int) -> None:
    if count <= 0:
        return
    with _cache_lock:
        _cache_metrics["bypassed"] += count
