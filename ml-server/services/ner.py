import spacy

_nlp = None

TARGET_TYPES = {"PERSON", "ORG", "PRODUCT", "EVENT", "GPE", "NORP", "FAC"}


def get_nlp():
    global _nlp
    if _nlp is None:
        try:
            _nlp = spacy.load("zh_core_web_sm")
        except Exception:
            _nlp = spacy.blank("zh")
    return _nlp


def extract_entities(text: str) -> list[dict]:
    """Extract named entities from text. Returns list of {name, type, start, end}."""
    nlp = get_nlp()
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
