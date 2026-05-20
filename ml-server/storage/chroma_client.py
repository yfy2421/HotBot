import logging
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

import chromadb
from chromadb.config import Settings
from config import CHROMA_PERSIST_DIR

_client = None
_logger = logging.getLogger(__name__)


def get_client():
    global _client
    if _client is None:
        _client = chromadb.PersistentClient(
            path=CHROMA_PERSIST_DIR,
            settings=Settings(anonymized_telemetry=False),
        )
    return _client


def get_or_create(name: str):
    client = get_client()
    return client.get_or_create_collection(name=name)


# ---- News short-term tracking ----

def add_news(news_id: str, text: str, vector: list[float], metadata: dict):
    col = get_or_create("news")
    enriched_metadata = _with_storage_metadata(metadata)
    col.add(
        ids=[news_id],
        documents=[text],
        embeddings=[vector],
        metadatas=[enriched_metadata],
    )


def query_similar(vector: list[float], days: int = 7, threshold: float = 0.8) -> list[dict]:
    """Query similar news within N days. Returns list of matched items."""
    col = get_or_create("news")
    try:
        results = col.query(
            query_embeddings=[vector],
            n_results=25 if days and days > 0 else 5,
        )
    except Exception:
        _logger.exception("Chroma news query failed")
        return []

    matched = []
    cutoff = _build_cutoff(days)
    if results and results["ids"] and results["ids"][0]:
        for i, doc_id in enumerate(results["ids"][0]):
            distance = results["distances"][0][i] if results.get("distances") else 0
            similarity = 1 - distance
            metadata = results["metadatas"][0][i] if results.get("metadatas") else {}
            if cutoff is not None and not _metadata_within_days(metadata, cutoff):
                continue
            if similarity > threshold:
                matched.append({
                    "id": doc_id,
                    "similarity": similarity,
                    "metadata": metadata,
                    "text": results["documents"][0][i] if results.get("documents") else "",
                })
    matched.sort(key=lambda item: item["similarity"], reverse=True)
    return matched[:5]


# ---- Entity long-term tracking ----

def add_entity(entity_id: str, entity_name: str, entity_type: str, vector: list[float], metadata: dict):
    col = get_or_create("entities")
    enriched_metadata = _with_storage_metadata(metadata)
    col.add(
        ids=[entity_id],
        documents=[f"{entity_name} [{entity_type}]"],
        embeddings=[vector],
        metadatas=[enriched_metadata],
    )


def query_entity_history(vector: list[float], threshold: float = 0.75) -> list[dict]:
    """Find historical entities with similar vectors."""
    col = get_or_create("entities")
    try:
        results = col.query(
            query_embeddings=[vector],
            n_results=10,
        )
    except Exception:
        _logger.exception("Chroma entity query failed")
        return []

    matched = []
    if results and results["ids"] and results["ids"][0]:
        for i, doc_id in enumerate(results["ids"][0]):
            distance = results["distances"][0][i] if results.get("distances") else 0
            similarity = 1 - distance
            if similarity > threshold:
                matched.append({
                    "id": doc_id,
                    "similarity": similarity,
                    "metadata": results["metadatas"][0][i] if results.get("metadatas") else {},
                    "text": results["documents"][0][i] if results.get("documents") else "",
                })
    return matched


def _with_storage_metadata(metadata: dict | None) -> dict:
    enriched = dict(metadata or {})
    now = datetime.now(timezone.utc)
    enriched.setdefault("stored_at_epoch", int(now.timestamp() * 1000))
    enriched.setdefault("date", now.date().isoformat())
    return enriched


def _build_cutoff(days: int) -> datetime | None:
    if days <= 0:
        return None
    return datetime.now(timezone.utc) - timedelta(days=days)


def _metadata_within_days(metadata: dict | None, cutoff: datetime) -> bool:
    item_time = _extract_metadata_time(metadata)
    return item_time is not None and item_time >= cutoff


def _extract_metadata_time(metadata: dict | None) -> datetime | None:
    if not isinstance(metadata, dict):
        return None

    for key in ("stored_at_epoch", "tracked_at_epoch", "fetched_at_epoch", "published_at_epoch"):
        parsed = _parse_datetime(metadata.get(key))
        if parsed is not None:
            return parsed

    for key in ("fetched_at", "published_at", "publish_time", "date"):
        parsed = _parse_datetime(metadata.get(key))
        if parsed is not None:
            return parsed

    return None


def _parse_datetime(value) -> datetime | None:
    if value is None:
        return None

    if isinstance(value, (int, float)):
        seconds = float(value) / 1000 if float(value) > 10_000_000_000 else float(value)
        return datetime.fromtimestamp(seconds, tz=timezone.utc)

    text = str(value).strip()
    if not text:
        return None

    try:
        return _parse_datetime(float(text))
    except ValueError:
        pass

    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except ValueError:
        pass

    try:
        parsed = parsedate_to_datetime(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except (TypeError, ValueError):
        pass

    try:
        return datetime.strptime(text, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    except ValueError:
        return None
