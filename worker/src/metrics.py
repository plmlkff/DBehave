import asyncio
import logging
import os
import time
from typing import Any
from urllib.error import URLError
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener

import psutil

from src.config import Settings

logger = logging.getLogger(__name__)


def _label_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _sample(name: str, labels: dict[str, str], value: float | int) -> str:
    label_text = ""
    if labels:
        rendered = ",".join(
            f'{key}="{_label_value(value)}"' for key, value in labels.items()
        )
        label_text = f"{{{rendered}}}"
    return f"{name}{label_text} {value}\n"


def render_worker_metrics(
    *,
    process_cpu_seconds: float,
    process_memory_bytes: int,
    filesystem_total_bytes: int,
    filesystem_used_bytes: int,
    filesystem_free_bytes: int,
    active_jobs: int,
    reserved_cpu_cores: float,
    reserved_memory_bytes: int,
    docker_images_bytes: int,
    docker_containers_bytes: int,
    docker_volumes_bytes: int,
) -> bytes:
    samples = (
        ("dbehave_worker_process_cpu_seconds_total", process_cpu_seconds),
        ("dbehave_worker_process_memory_rss_bytes", process_memory_bytes),
        ("dbehave_worker_filesystem_size_bytes", filesystem_total_bytes),
        ("dbehave_worker_filesystem_used_bytes", filesystem_used_bytes),
        ("dbehave_worker_filesystem_free_bytes", filesystem_free_bytes),
        ("dbehave_worker_active_jobs", active_jobs),
        ("dbehave_worker_reserved_cpu_cores", reserved_cpu_cores),
        ("dbehave_worker_reserved_memory_bytes", reserved_memory_bytes),
        ("dbehave_worker_docker_images_bytes", docker_images_bytes),
        ("dbehave_worker_docker_containers_bytes", docker_containers_bytes),
        ("dbehave_worker_docker_volumes_bytes", docker_volumes_bytes),
    )
    return "".join(_sample(name, {}, value) for name, value in samples).encode()


def render_task_container_metrics(
    containers: dict[str, dict[str, Any]],
) -> bytes:
    lines: list[str] = []
    for role, stats in containers.items():
        labels = {"role": role}
        cpu_usage = stats.get("cpu_stats", {}).get("cpu_usage", {})
        cpu_nanoseconds = cpu_usage.get("total_usage")
        if isinstance(cpu_nanoseconds, (int, float)):
            lines.append(
                _sample(
                    "dbehave_worker_container_cpu_seconds_total",
                    labels,
                    cpu_nanoseconds / 1_000_000_000,
                )
            )

        memory_stats = stats.get("memory_stats", {})
        memory_usage = memory_stats.get("usage")
        memory_limit = memory_stats.get("limit")
        memory_detail = memory_stats.get("stats", {})
        cache = memory_detail.get(
            "inactive_file",
            memory_detail.get("total_inactive_file", memory_detail.get("cache", 0)),
        )
        if isinstance(memory_usage, (int, float)):
            lines.append(
                _sample(
                    "dbehave_worker_container_memory_bytes",
                    labels,
                    max(0, memory_usage - cache),
                )
            )
        if isinstance(memory_limit, (int, float)):
            lines.append(
                _sample(
                    "dbehave_worker_container_memory_limit_bytes",
                    labels,
                    memory_limit,
                )
            )

        read_bytes = 0
        write_bytes = 0
        for entry in (
            stats.get("blkio_stats", {}).get("io_service_bytes_recursive") or []
        ):
            operation = str(entry.get("op", "")).lower()
            if operation == "read":
                read_bytes += int(entry.get("value", 0))
            elif operation == "write":
                write_bytes += int(entry.get("value", 0))
        lines.append(
            _sample(
                "dbehave_worker_container_block_io_read_bytes_total",
                labels,
                read_bytes,
            )
        )
        lines.append(
            _sample(
                "dbehave_worker_container_block_io_write_bytes_total",
                labels,
                write_bytes,
            )
        )

        pids = stats.get("pids_stats", {}).get("current")
        if isinstance(pids, (int, float)):
            lines.append(_sample("dbehave_worker_container_pids", labels, pids))

    return "".join(lines).encode()


def push_metrics(
    pushgateway_url: str,
    job: str,
    grouping: dict[str, str],
    payload: bytes,
) -> None:
    path = f"/metrics/job/{quote(job, safe='')}"
    for key, value in grouping.items():
        path += f"/{quote(key, safe='')}/{quote(value, safe='')}"
    request = Request(
        f"{pushgateway_url.rstrip('/')}{path}",
        data=payload,
        headers={"Content-Type": "text/plain; version=0.0.4; charset=utf-8"},
        method="PUT",
    )
    opener = build_opener(ProxyHandler({}))
    with opener.open(request, timeout=3) as response:
        if response.status >= 300:
            raise RuntimeError(f"Pushgateway returned HTTP {response.status}")


def delete_metrics_group(
    pushgateway_url: str,
    job: str,
    grouping: dict[str, str],
) -> None:
    path = f"/metrics/job/{quote(job, safe='')}"
    for key, value in grouping.items():
        path += f"/{quote(key, safe='')}/{quote(value, safe='')}"
    request = Request(f"{pushgateway_url.rstrip('/')}{path}", method="DELETE")
    opener = build_opener(ProxyHandler({}))
    with opener.open(request, timeout=3) as response:
        if response.status >= 300:
            raise RuntimeError(f"Pushgateway returned HTTP {response.status}")


class WorkerMetricsPublisher:
    def __init__(self, settings: Settings, client: Any) -> None:
        self.settings = settings
        self.client = client
        self.process = psutil.Process()
        self._last_docker_df = 0.0
        self._docker_bytes = (0, 0, 0)

    async def publish(
        self,
        *,
        active_jobs: int,
        reserved_cpu_cores: float,
        reserved_memory_bytes: int,
    ) -> None:
        payload = await asyncio.to_thread(
            self._collect,
            active_jobs,
            reserved_cpu_cores,
            reserved_memory_bytes,
        )
        await asyncio.to_thread(
            push_metrics,
            self.settings.prometheus_pushgateway_url or "",
            "dbehave_worker",
            {"instance": self.settings.instance_id},
            payload,
        )

    def _collect(
        self,
        active_jobs: int,
        reserved_cpu_cores: float,
        reserved_memory_bytes: int,
    ) -> bytes:
        cpu_times = self.process.cpu_times()
        process_cpu_seconds = cpu_times.user + cpu_times.system
        process_memory_bytes = self.process.memory_info().rss
        filesystem = psutil.disk_usage(os.path.abspath(os.sep))

        now = time.monotonic()
        if now - self._last_docker_df >= 30:
            try:
                self._docker_bytes = self._read_docker_disk_usage()
                self._last_docker_df = now
            except Exception:
                logger.warning("Could not collect Docker disk usage metrics", exc_info=True)

        return render_worker_metrics(
            process_cpu_seconds=process_cpu_seconds,
            process_memory_bytes=process_memory_bytes,
            filesystem_total_bytes=filesystem.total,
            filesystem_used_bytes=filesystem.used,
            filesystem_free_bytes=filesystem.free,
            active_jobs=active_jobs,
            reserved_cpu_cores=reserved_cpu_cores,
            reserved_memory_bytes=reserved_memory_bytes,
            docker_images_bytes=self._docker_bytes[0],
            docker_containers_bytes=self._docker_bytes[1],
            docker_volumes_bytes=self._docker_bytes[2],
        )

    def _read_docker_disk_usage(self) -> tuple[int, int, int]:
        usage = self.client.df()
        images_bytes = sum(
            max(0, int(item.get("Size") or 0)) for item in usage.get("Images") or []
        )
        containers_bytes = sum(
            max(0, int(item.get("SizeRw") or 0))
            for item in usage.get("Containers") or []
        )
        volumes_bytes = sum(
            max(0, int((item.get("UsageData") or {}).get("Size") or 0))
            for item in usage.get("Volumes") or []
        )
        return images_bytes, containers_bytes, volumes_bytes


def task_metrics_group(instance_id: str, task_id: str) -> dict[str, str]:
    return {"instance": instance_id, "run_id": task_id}


def log_metrics_error(message: str, error: Exception) -> None:
    if isinstance(error, (OSError, URLError, TimeoutError, RuntimeError)):
        logger.warning("%s: %s", message, error)
    else:
        logger.warning("%s: %s", message, error, exc_info=True)
