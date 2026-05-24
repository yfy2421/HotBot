import asyncio
import unittest
from unittest.mock import patch

from services import task_dispatch


class TaskDispatchMetricsTest(unittest.TestCase):

    def test_run_embed_task_updates_executor_metrics(self):
        before = task_dispatch.get_executor_metrics()["semantic"]

        with patch("services.task_dispatch.encode", return_value=[[0.1, 0.2]]):
            result = asyncio.run(task_dispatch.run_embed_task(["hello"]))

        after = task_dispatch.get_executor_metrics()["semantic"]

        self.assertEqual([[0.1, 0.2]], result)
        self.assertEqual(before["submitted_tasks"] + 1, after["submitted_tasks"])
        self.assertEqual(before["completed_tasks"] + 1, after["completed_tasks"])
        self.assertEqual(before["failed_tasks"], after["failed_tasks"])
        self.assertEqual(0, after["in_flight_tasks"])
        self.assertTrue(after["healthy"])


if __name__ == "__main__":
    unittest.main()