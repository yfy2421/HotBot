import unittest
from unittest.mock import patch

from services import translation
from services.translation import translate_texts


class TranslationServiceTest(unittest.TestCase):

    @patch("services.translation.translate_batch", return_value=["  Waymo 暂停高速服务  ", ""])
    @patch("services.translation.get_translation_backend", return_value={"backend": True})
    def test_translate_texts_normalizes_outputs(self, _backend, _translate_batch):
        translated = translate_texts(["first", "second"], "title")

        self.assertEqual(["Waymo 暂停高速服务", ""], translated)

    @patch("services.translation.get_translation_backend", return_value=None)
    def test_translate_texts_returns_blanks_when_backend_unavailable(self, _backend):
        translated = translate_texts(["hello", "world"], "summary")

        self.assertEqual(["", ""], translated)

    @patch.object(translation, "ensure_translation_backend_async")
    @patch.object(translation, "_translation_backend", None)
    @patch.object(translation, "_translation_backend_failed", False)
    def test_get_translation_backend_non_blocking_triggers_async_warmup(self, mock_warmup):
        backend = translation.get_translation_backend(wait=False)

        self.assertIsNone(backend)
        mock_warmup.assert_called_once()


if __name__ == "__main__":
    unittest.main()