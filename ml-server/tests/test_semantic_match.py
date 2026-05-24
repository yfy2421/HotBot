import unittest
from unittest.mock import Mock, patch

from services.semantic_match import rank_candidates


class SemanticMatchTest(unittest.TestCase):

    @patch("services.semantic_match.get_reranker")
    @patch("services.semantic_match.encode")
    def test_rank_candidates_prefers_reranker_when_available(self, mock_encode, mock_get_reranker):
        mock_encode.side_effect = [
            [[1.0, 0.0]],
            [[0.6, 0.4], [0.2, 0.8]],
        ]
        reranker = Mock()
        reranker.predict.return_value = [4.0, 0.3]
        mock_get_reranker.return_value = reranker

        ranked = rank_candidates("waymo construction", ["candidate a", "candidate b"], top_k=2)

        self.assertEqual(2, len(ranked))
        self.assertEqual(0, ranked[0]["index"])
        self.assertGreater(ranked[0]["score"], ranked[1]["score"])
        self.assertGreater(ranked[0]["rerank_score"], ranked[1]["rerank_score"])

    @patch("services.semantic_match.get_reranker")
    @patch("services.semantic_match.encode")
    def test_rank_candidates_falls_back_to_embedding_scores(self, mock_encode, mock_get_reranker):
        mock_encode.side_effect = [
            [[1.0, 0.0]],
            [[0.9, 0.1], [0.1, 0.9]],
        ]
        mock_get_reranker.return_value = None

        ranked = rank_candidates("waymo", ["candidate a", "candidate b"], top_k=2)

        self.assertEqual([0, 1], [item["index"] for item in ranked])
        self.assertAlmostEqual(ranked[0]["embed_score"], ranked[0]["score"], places=6)


if __name__ == "__main__":
    unittest.main()