import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import main


class MainCpuDispatchTest(unittest.TestCase):

    def test_embedding_cache_metrics_endpoint_returns_metrics(self):
        cache_metrics = {"enabled": True, "size": 2, "hits": 3}
        with patch.object(main, "ensure_translation_backend_async"):
            with patch.object(main, "embed_cache_metrics", return_value=cache_metrics):
                with TestClient(main.app) as client:
                    response = client.get("/api/metrics/embedding-cache")

        self.assertEqual(200, response.status_code)
        self.assertEqual({"embedding_cache": cache_metrics}, response.json())

    def test_executor_metrics_endpoint_returns_metrics(self):
        metrics = {
            "semantic": {"healthy": True, "max_workers": 1},
            "translation": {"healthy": True, "max_workers": 1},
        }
        with patch.object(main, "ensure_translation_backend_async"):
            with patch.object(main, "get_executor_metrics", return_value=metrics):
                with TestClient(main.app) as client:
                    response = client.get("/api/metrics/executors")

        self.assertEqual(200, response.status_code)
        self.assertEqual({"executors": metrics}, response.json())

    def test_ready_includes_executor_metrics(self):
        metrics = {
            "semantic": {"healthy": True, "max_workers": 1},
            "translation": {"healthy": True, "max_workers": 1},
        }
        cache_metrics = {"enabled": True, "size": 2, "hits": 3}
        with patch.object(main, "ensure_translation_backend_async"):
            with patch.object(main, "translation_backend_status", return_value="ready"):
                with patch.object(main, "get_executor_metrics", return_value=metrics):
                    with patch.object(main, "ner_backend_status", return_value="ready"):
                        with patch.object(main, "embed_cache_metrics", return_value=cache_metrics):
                            with TestClient(main.app) as client:
                                response = client.get("/api/ready")

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {
                "ready": True,
                "translation_status": "ready",
                "cpu_dispatch_ready": True,
                "ner_status": "ready",
                "embedding_cache": cache_metrics,
                "executors": metrics,
            },
            response.json(),
        )

    def test_ready_reports_unhealthy_executor(self):
        metrics = {
            "semantic": {"healthy": False, "max_workers": 1},
            "translation": {"healthy": True, "max_workers": 1},
        }
        cache_metrics = {"enabled": True, "size": 0, "hits": 0}
        with patch.object(main, "ensure_translation_backend_async"):
            with patch.object(main, "translation_backend_status", return_value="ready"):
                with patch.object(main, "get_executor_metrics", return_value=metrics):
                    with patch.object(main, "ner_backend_status", return_value="fallback_blank_zh"):
                        with patch.object(main, "embed_cache_metrics", return_value=cache_metrics):
                            with TestClient(main.app) as client:
                                response = client.get("/api/ready")

        self.assertEqual(200, response.status_code)
        self.assertEqual(False, response.json()["ready"])
        self.assertEqual(False, response.json()["cpu_dispatch_ready"])
        self.assertEqual(metrics, response.json()["executors"])
        self.assertEqual("fallback_blank_zh", response.json()["ner_status"])
        self.assertEqual(cache_metrics, response.json()["embedding_cache"])

    def test_embed_endpoint_dispatches_to_semantic_executor(self):
        with patch.object(main, "ensure_translation_backend_async"):
            with patch.object(main, "run_embed_task", new_callable=AsyncMock, return_value=[[0.1, 0.2]]) as mock_run:
                with TestClient(main.app) as client:
                    response = client.post("/api/embed", json={"texts": ["hello"]})

        self.assertEqual(200, response.status_code)
        self.assertEqual({"vectors": [[0.1, 0.2]]}, response.json())
        mock_run.assert_awaited_once_with(["hello"])

    def test_semantic_rank_endpoint_dispatches_to_semantic_executor(self):
        matches = [{"index": 0, "candidate": "Waymo", "score": 0.9}]
        with patch.object(main, "ensure_translation_backend_async"):
            with patch.object(main, "run_semantic_rank_task", new_callable=AsyncMock, return_value=matches) as mock_run:
                with TestClient(main.app) as client:
                    response = client.post(
                        "/api/semantic/rank",
                        json={"query": "waymo", "candidates": ["Waymo"], "top_k": 1},
                    )

        self.assertEqual(200, response.status_code)
        self.assertEqual({"matches": matches}, response.json())
        mock_run.assert_awaited_once_with("waymo", ["Waymo"], 1)

    def test_translate_endpoint_dispatches_to_translation_executor(self):
        with patch.object(main, "ensure_translation_backend_async"):
            with patch.object(main, "run_translate_task", new_callable=AsyncMock, return_value=["译文"]) as mock_run:
                with TestClient(main.app) as client:
                    response = client.post(
                        "/api/translate",
                        json={"texts": ["hello"], "text_type": "title"},
                    )

        self.assertEqual(200, response.status_code)
        self.assertEqual({"translations": ["译文"]}, response.json())
        mock_run.assert_awaited_once_with(["hello"], "title")


if __name__ == "__main__":
    unittest.main()