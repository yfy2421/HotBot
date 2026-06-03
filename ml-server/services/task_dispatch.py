from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from functools import partial
import threading
import time
from typing import Any, TypeVar

from config import get_cpu_task_config
from services.embed import encode
from services.intent import classify_intent
from services.semantic_match import rank_candidates
from services.translation import translate_texts


T = TypeVar("T")


@dataclass
class ExecutorTelemetry:
    name: str
    max_workers: int
    task_types: tuple[str, ...]
    submitted_tasks: int = 0
    completed_tasks: int = 0
    failed_tasks: int = 0
    in_flight_tasks: int = 0
    total_duration_ms: float = 0.0
    last_duration_ms: float | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def mark_submitted(self) -> None:
        with self._lock:
            self.submitted_tasks += 1
            self.in_flight_tasks += 1

    def mark_finished(self, duration_ms: float, failed: bool) -> None:
        with self._lock:
            if self.in_flight_tasks > 0:
                self.in_flight_tasks -= 1
            if failed:
                self.failed_tasks += 1
            else:
                self.completed_tasks += 1
            self.total_duration_ms += duration_ms
            self.last_duration_ms = duration_ms

    def snapshot(self, executor: ThreadPoolExecutor) -> dict[str, Any]:
        with self._lock:
            submitted_tasks = self.submitted_tasks
            completed_tasks = self.completed_tasks
            failed_tasks = self.failed_tasks
            in_flight_tasks = self.in_flight_tasks
            total_duration_ms = self.total_duration_ms
            last_duration_ms = self.last_duration_ms
        observed_tasks = completed_tasks + failed_tasks
        shutdown = bool(getattr(executor, "_shutdown", False))
        broken = bool(getattr(executor, "_broken", False))
        return {
            "name": self.name,
            "task_types": list(self.task_types),
            "max_workers": self.max_workers,
            "submitted_tasks": submitted_tasks,
            "completed_tasks": completed_tasks,
            "failed_tasks": failed_tasks,
            "in_flight_tasks": in_flight_tasks,
            "queue_size": _executor_queue_size(executor),
            "avg_duration_ms": round(total_duration_ms / observed_tasks, 3) if observed_tasks else None,
            "last_duration_ms": round(last_duration_ms, 3) if last_duration_ms is not None else None,
            "shutdown": shutdown,
            "broken": broken,
            "healthy": not shutdown and not broken,
        }

_task_config = get_cpu_task_config()
_semantic_executor = ThreadPoolExecutor(
    max_workers=_task_config["semantic_executor_workers"],
    thread_name_prefix="ml-semantic",
)
_semantic_telemetry = ExecutorTelemetry(
    name="semantic",
    max_workers=_task_config["semantic_executor_workers"],
    task_types=("embed", "semantic_rank", "intent_classify"),
)
_translation_executor = ThreadPoolExecutor(
    max_workers=_task_config["translation_executor_workers"],
    thread_name_prefix="ml-translation",
)
_translation_telemetry = ExecutorTelemetry(
    name="translation",
    max_workers=_task_config["translation_executor_workers"],
    task_types=("translate",),
)


async def run_embed_task(texts: list[str]) -> list[list[float]]:
    return await _run_on_executor(_semantic_executor, _semantic_telemetry, encode, texts)


async def run_semantic_rank_task(query: str,
                                 candidates: list[str],
                                 top_k: int | None = None) -> list[dict[str, object]]:
    return await _run_on_executor(_semantic_executor, _semantic_telemetry, rank_candidates, query, candidates, top_k)


async def run_intent_classify_task(text: str) -> dict:
    return await _run_on_executor(_semantic_executor, _semantic_telemetry, classify_intent, text)


async def run_translate_task(texts: list[str], text_type: str | None = None) -> list[str]:
    return await _run_on_executor(_translation_executor, _translation_telemetry, translate_texts, texts, text_type)


def get_executor_metrics() -> dict[str, dict[str, Any]]:
    return {
        "semantic": _semantic_telemetry.snapshot(_semantic_executor),
        "translation": _translation_telemetry.snapshot(_translation_executor),
    }


def cpu_dispatch_ready(metrics: dict[str, dict[str, Any]] | None = None) -> bool:
    current_metrics = metrics or get_executor_metrics()
    return all(item.get("healthy", False) for item in current_metrics.values())


async def _run_on_executor(executor: ThreadPoolExecutor,
                           telemetry: ExecutorTelemetry,
                           func,
                           *args,
                           **kwargs) -> T:
    loop = asyncio.get_running_loop()
    telemetry.mark_submitted()
    bound = partial(_execute_tracked, telemetry, func, *args, **kwargs)
    try:
        future = loop.run_in_executor(executor, bound)
    except Exception:
        telemetry.mark_finished(0.0, failed=True)
        raise
    return await future


def _execute_tracked(telemetry: ExecutorTelemetry, func, *args, **kwargs):
    start = time.perf_counter()
    failed = False
    try:
        return func(*args, **kwargs)
    except Exception:
        failed = True
        raise
    finally:
        telemetry.mark_finished((time.perf_counter() - start) * 1000, failed=failed)


def _executor_queue_size(executor: ThreadPoolExecutor) -> int | None:
    work_queue = getattr(executor, "_work_queue", None)
    if work_queue is None or not hasattr(work_queue, "qsize"):
        return None
    try:
        return work_queue.qsize()
    except NotImplementedError:
        return None