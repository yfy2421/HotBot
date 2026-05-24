import logging

import spacy

from config import NER_MODEL_NAME


logger = logging.getLogger(__name__)

_nlp = None
_ner_status = "idle"

TARGET_TYPES = {"PERSON", "ORG", "PRODUCT", "EVENT", "GPE", "NORP", "FAC"}


def get_nlp():
    global _nlp
    global _ner_status
    if _nlp is None:
        try:
            _nlp = spacy.load(NER_MODEL_NAME)
            _ner_status = "ready"
        except Exception as exc:
            logger.warning(
                "NER model unavailable, falling back to blank zh pipeline model=%s exception=%s message=%s",
                NER_MODEL_NAME,
                exc.__class__.__name__,
                exc,
            )
            try:
                _nlp = spacy.blank("zh")
                _ner_status = "fallback_blank_zh"
            except Exception:
                logger.exception("Failed to initialize fallback blank zh pipeline model=%s", NER_MODEL_NAME)
                _nlp = None
                _ner_status = "failed"
    return _nlp


def ner_backend_status() -> str:
    return _ner_status


def extract_entities(text: str) -> list[dict]:
    """Extract named entities from text. Returns list of {name, type, start, end}."""
    nlp = get_nlp()
    if nlp is None:
        return []
    doc = nlp(text)
    entities = []
    seen = set()
    for ent in doc.ents:
        if ent.label_ in TARGET_TYPES:
            key = (ent.text.strip(), ent.label_)
            if key not in seen:
                seen.add(key)
                entities.append({
                    "name": ent.text.strip(),
                    "type": ent.label_,
                })
    return entities
