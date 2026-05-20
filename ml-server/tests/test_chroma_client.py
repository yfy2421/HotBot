import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

from storage import chroma_client


class QuerySimilarDaysFilterTest(unittest.TestCase):

    def test_query_similar_filters_outdated_records(self):
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        old_ms = int((datetime.now(timezone.utc) - timedelta(days=10)).timestamp() * 1000)

        fake_collection = MagicMock()
        fake_collection.query.return_value = {
            "ids": [["recent", "old"]],
            "distances": [[0.05, 0.01]],
            "metadatas": [[
                {"stored_at_epoch": now_ms},
                {"stored_at_epoch": old_ms},
            ]],
            "documents": [["recent doc", "old doc"]],
        }

        with patch.object(chroma_client, "get_or_create", return_value=fake_collection):
            results = chroma_client.query_similar([0.1, 0.2], days=7, threshold=0.8)

        self.assertEqual(1, len(results))
        self.assertEqual("recent", results[0]["id"])

    def test_add_news_enriches_storage_metadata(self):
        fake_collection = MagicMock()

        with patch.object(chroma_client, "get_or_create", return_value=fake_collection):
            chroma_client.add_news("n1", "text", [0.1], {"title": "sample"})

        metadata = fake_collection.add.call_args.kwargs["metadatas"][0]
        self.assertIn("stored_at_epoch", metadata)
        self.assertIn("date", metadata)
        self.assertEqual("sample", metadata["title"])


if __name__ == "__main__":
    unittest.main()