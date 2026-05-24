import logging
import threading
from typing import Any

from config import TRANSLATION_MODEL_NAME, TRANSLATION_SOURCE_LANG, TRANSLATION_TARGET_LANG


logger = logging.getLogger(__name__)

_translation_backend = None
_translation_backend_failed = False
_translation_backend_loading = False
_translation_backend_lock = threading.Lock()


def ensure_translation_backend_async() -> None:
    global _translation_backend_loading
    if _translation_backend is not None or _translation_backend_failed:
        return
    with _translation_backend_lock:
        if _translation_backend is not None or _translation_backend_failed or _translation_backend_loading:
            return
        _translation_backend_loading = True
        thread = threading.Thread(target=_load_translation_backend, name="translation-warmup", daemon=True)
        thread.start()


def _load_translation_backend() -> None:
    global _translation_backend
    global _translation_backend_failed
    global _translation_backend_loading
    try:
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        import torch

        tokenizer = AutoTokenizer.from_pretrained(TRANSLATION_MODEL_NAME)
        model = AutoModelForSeq2SeqLM.from_pretrained(TRANSLATION_MODEL_NAME)
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model.to(device)
        model.eval()
        _translation_backend = {
            "tokenizer": tokenizer,
            "model": model,
            "device": device,
            "is_nllb": "nllb" in TRANSLATION_MODEL_NAME.lower(),
        }
        logger.info("Translation backend ready model=%s device=%s", TRANSLATION_MODEL_NAME, device)
    except Exception:
        logger.exception("Failed to load translation model=%s", TRANSLATION_MODEL_NAME)
        _translation_backend_failed = True
    finally:
        _translation_backend_loading = False


def get_translation_backend(wait: bool = False) -> dict[str, Any] | None:
    if _translation_backend_failed:
        return None
    if _translation_backend is not None:
        return _translation_backend
    if wait:
        with _translation_backend_lock:
            if _translation_backend is None and not _translation_backend_failed and not _translation_backend_loading:
                _load_translation_backend()
        return _translation_backend
    ensure_translation_backend_async()
    return _translation_backend


def translation_backend_status() -> str:
    if _translation_backend is not None:
        return "ready"
    if _translation_backend_failed:
        return "failed"
    if _translation_backend_loading:
        return "loading"
    return "idle"


def translate_texts(texts: list[str], text_type: str | None = None) -> list[str]:
    normalized_texts = [normalize_input_text(text) for text in texts or []]
    if not normalized_texts:
        return []

    backend = get_translation_backend(wait=False)
    if backend is None:
        logger.info("Translation backend not ready yet, returning untranslated fallback")
        return ["" for _ in normalized_texts]

    results = ["" for _ in normalized_texts]
    batch_size = 8
    for start in range(0, len(normalized_texts), batch_size):
        end = start + batch_size
        batch = normalized_texts[start:end]
        translated_batch = translate_batch(batch, backend, text_type)
        for index, translated in enumerate(translated_batch):
            results[start + index] = normalize_output_text(translated)
    return results


def translate_batch(texts: list[str], backend: dict[str, Any], text_type: str | None) -> list[str]:
    if not texts:
        return []
    try:
        import torch
    except Exception:
        logger.exception("Torch import failed for translation")
        return ["" for _ in texts]

    tokenizer = backend["tokenizer"]
    model = backend["model"]
    device = backend["device"]
    is_nllb = backend["is_nllb"]

    non_blank_positions = [index for index, text in enumerate(texts) if text]
    if not non_blank_positions:
        return ["" for _ in texts]

    non_blank_texts = [texts[index] for index in non_blank_positions]
    if is_nllb:
        tokenizer.src_lang = TRANSLATION_SOURCE_LANG

    encoded = tokenizer(
        non_blank_texts,
        padding=True,
        truncation=True,
        max_length=384,
        return_tensors="pt",
    )
    encoded = {key: value.to(device) for key, value in encoded.items()}

    generate_kwargs = {
        "max_new_tokens": 96 if text_type == "title" else 256,
        "num_beams": 4,
    }
    if is_nllb:
        generate_kwargs["forced_bos_token_id"] = tokenizer.convert_tokens_to_ids(TRANSLATION_TARGET_LANG)

    with torch.no_grad():
        generated = model.generate(**encoded, **generate_kwargs)
    decoded = tokenizer.batch_decode(generated, skip_special_tokens=True)

    results = ["" for _ in texts]
    for position, translated in zip(non_blank_positions, decoded):
        results[position] = translated
    return results


def normalize_input_text(text: str | None) -> str:
    return (text or "").strip()


def normalize_output_text(text: str | None) -> str:
    return " ".join((text or "").split())
