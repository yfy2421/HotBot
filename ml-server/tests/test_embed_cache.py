import unittest
from unittest.mock import Mock, patch

from services import embed


class _FakeEmbeddings:
    def __init__(self, vectors):
        self._vectors = vectors

    def tolist(self):
        return self._vectors


class EmbedCacheTest(unittest.TestCase):

    def tearDown(self):
        embed.clear_embed_cache()

    @patch("services.embed.get_embed_cache_config", return_value={"enabled": True, "max_entries": 4, "max_text_length": 32})
    def test_encode_uses_cache_for_short_texts(self, _config):
        model = Mock()
        model.encode.return_value = _FakeEmbeddings([[0.1, 0.2]])

        with patch("services.embed.get_model", return_value=model):
            first = embed.encode(["short text"])
            second = embed.encode(["short text"])

        self.assertEqual([[0.1, 0.2]], first)
        self.assertEqual([[0.1, 0.2]], second)
        model.encode.assert_called_once_with(["short text"], normalize_embeddings=True)
        metrics = embed.embed_cache_metrics()
        self.assertEqual(1, metrics["hits"])
        self.assertEqual(1, metrics["misses"])
        self.assertEqual(1, metrics["stores"])
        self.assertEqual(1, metrics["size"])

    @patch("services.embed.get_embed_cache_config", return_value={"enabled": True, "max_entries": 4, "max_text_length": 8})
    def test_encode_bypasses_cache_for_long_texts(self, _config):
        model = Mock()
        long_text = "this text is definitely longer than eight chars"
        model.encode.side_effect = [
            _FakeEmbeddings([[0.3, 0.4]]),
            _FakeEmbeddings([[0.3, 0.4]]),
        ]

        with patch("services.embed.get_model", return_value=model):
            embed.encode([long_text])
            embed.encode([long_text])

        self.assertEqual(2, model.encode.call_count)
        metrics = embed.embed_cache_metrics()
        self.assertEqual(0, metrics["hits"])
        self.assertEqual(0, metrics["stores"])
        self.assertEqual(2, metrics["bypassed"])


if __name__ == "__main__":
    unittest.main()