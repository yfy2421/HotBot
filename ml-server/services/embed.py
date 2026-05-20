from sentence_transformers import SentenceTransformer
from config import EMBED_MODEL_NAME

_model = None


def get_model():
    global _model
    if _model is None:
        _model = SentenceTransformer(EMBED_MODEL_NAME)
    return _model


def encode(texts: list[str]) -> list[list[float]]:
    """Encode a list of texts into embedding vectors."""
    model = get_model()
    embeddings = model.encode(texts, normalize_embeddings=True)
    return embeddings.tolist()
