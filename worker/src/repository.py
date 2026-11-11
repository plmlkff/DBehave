import asyncio
import json
import logging
import math
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import UUID

import asyncpg

from src.config import Settings
from src.models import TaskStatus, TaskSubmission

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class TaskRecord:
    id: str
    status: TaskStatus
    payload: TaskSubmission
    created_at: str
    updated_at: str
    started_at: str | None
    finished_at: str | None
    exit_code: int | None
    error: str | None
    db_logs: str | None
    loadgen_logs: str | None


class TaskRepository:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.worker_id = settings.instance_id[:255]
        self._pool: asyncpg.Pool | None = None

    async def initialize(self) -> None:
        self._pool = await asyncpg.create_pool(
            host=self.settings.db_host,
            port=self.settings.db_port,
            database=self.settings.db_name,
            user=self.settings.db_user,
            password=self.settings.db_password.get_secret_value(),
            min_size=1,
            max_size=self.settings.max_concurrent_jobs + 2,
        )
        deadline = time.monotonic() + 90
        while True:
            try:
                async with self._pool.acquire() as connection:
                    await connection.execute("SELECT id FROM worker.tasks LIMIT 0")
                return
            except Exception:
                if time.monotonic() >= deadline:
                    await self.close()
                    raise
                logger.info("Waiting for backend migration to create worker.tasks")
                await asyncio.sleep(2)

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    async def create(self, task: TaskSubmission) -> None:
        pool = self._require_pool()
        payload = task.model_dump(mode="json")
        context = {
            "loaderImageName": payload["loadgen_image"],
            "databaseImageName": payload["db_image"],
            "workerOptions": {
                "dbTmpfsPaths": payload["db_tmpfs_paths"],
                "loadgenTmpfsPaths": payload["loadgen_tmpfs_paths"],
                "timeoutSeconds": task.timeout_seconds,
                "dbStartupGraceSeconds": task.db_startup_grace_seconds,
            },
            "loaderImageContext": {
                "environment": payload["loadgen_env"],
                "command": payload["loadgen_command"] or [],
                "core_max": task.cpu_cores * (1 - task.db_cpu_fraction),
                "mem_max": int(task.memory_mb * (1 - task.db_memory_fraction) * 1024 * 1024),
            },
            "databaseImageContext": {
                "environment": payload["db_env"],
                "command": payload["db_command"] or [],
                "core_max": task.cpu_cores * task.db_cpu_fraction,
                "mem_max": int(task.memory_mb * task.db_memory_fraction * 1024 * 1024),
            },
        }
        async with pool.acquire() as connection:
            await connection.execute(
                """INSERT INTO worker.tasks (id, state, context)
                   VALUES ($1, 'QUEUED', $2::jsonb)""",
                UUID(task.id), json.dumps(context),
            )

    async def list_new(self, limit: int = 100) -> list[TaskRecord]:
        pool = self._require_pool()
        rows = await pool.fetch(
            """SELECT id, state, created_at, updated_at, context, heartbeat, worker_id, error
               FROM worker.tasks
               WHERE state = 'QUEUED'
               ORDER BY created_at, id
               LIMIT $1""",
            limit,
        )
        records = []
        for row in rows:
            try:
                records.append(_to_record(row, self.settings))
            except Exception as exc:
                error = f"Invalid task context: {type(exc).__name__}: {exc}"
                logger.exception("Rejecting invalid queued task %s", row["id"])
                await pool.execute(
                    """UPDATE worker.tasks SET state = 'FAILED', error = $2
                       WHERE id = $1 AND state = 'QUEUED'""",
                    row["id"], _truncate(error, 8192),
                )
        return records

    async def claim(self, task_id: str) -> bool:
        pool = self._require_pool()
        row = await pool.fetchrow(
            """UPDATE worker.tasks
               SET state = 'RUNNING', worker_id = $2, heartbeat = clock_timestamp()
               WHERE id = $1::uuid AND state = 'QUEUED'
               RETURNING id""",
            UUID(task_id), self.worker_id,
        )
        return row is not None

    async def heartbeat(self, task_id: str) -> None:
        pool = self._require_pool()
        await pool.execute(
            """UPDATE worker.tasks SET heartbeat = clock_timestamp()
               WHERE id = $1::uuid AND worker_id = $2 AND state = 'RUNNING'""",
            UUID(task_id), self.worker_id,
        )

    async def finish(
        self,
        task_id: str,
        status: TaskStatus,
        *,
        exit_code: int | None = None,
        error: str | None = None,
        db_logs: str | None = None,
        loadgen_logs: str | None = None,
    ) -> None:
        if status not in (TaskStatus.SUCCEEDED, TaskStatus.FAILED):
            raise ValueError("finish() accepts only terminal task statuses")
        pool = self._require_pool()
        await pool.execute(
            """UPDATE worker.tasks
               SET state = $2, error = $3, heartbeat = clock_timestamp()
               WHERE id = $1::uuid AND worker_id = $4""",
            UUID(task_id), status.value, _truncate(error, 8192), self.worker_id,
        )

    async def fail_interrupted(self) -> None:
        pool = self._require_pool()
        await pool.execute(
            """UPDATE worker.tasks
               SET state = 'FAILED', error = 'Worker restarted while this job was running'
               WHERE state = 'RUNNING' AND worker_id = $1""",
            self.worker_id,
        )

    async def get(self, task_id: str) -> TaskRecord | None:
        pool = self._require_pool()
        row = await pool.fetchrow(
            """SELECT id, state, created_at, updated_at, context, heartbeat, worker_id, error
               FROM worker.tasks WHERE id = $1::uuid""",
            UUID(task_id),
        )
        return _to_record(row, self.settings) if row else None

    def _require_pool(self) -> asyncpg.Pool:
        if self._pool is None:
            raise RuntimeError("PostgreSQL repository is not initialized")
        return self._pool


def _to_record(row: asyncpg.Record, settings: Settings) -> TaskRecord:
    context: dict[str, Any] = row["context"]
    if isinstance(context, str):
        context = json.loads(context)
    payload = _to_submission(context, settings, str(row["id"]))
    state = row["state"]
    status = TaskStatus(state)
    created_at = _timestamp(row["created_at"])
    updated_at = _timestamp(row["updated_at"])
    return TaskRecord(
        id=str(row["id"]),
        status=status,
        payload=payload,
        created_at=created_at,
        updated_at=updated_at,
        started_at=None,
        finished_at=updated_at if state in {"SUCCEEDED", "FAILED"} else None,
        exit_code=None,
        error=row["error"],
        db_logs=None,
        loadgen_logs=None,
    )


def _to_submission(
    context: dict[str, Any], settings: Settings, task_id: str
) -> TaskSubmission:
    db_context = context["databaseImageContext"]
    loadgen_context = context["loaderImageContext"]
    options = context.get("workerOptions", {})
    default_cpu = max(0.1, settings.capacity_cores / settings.max_concurrent_jobs / 2)
    default_memory_mb = max(128, settings.capacity_memory_mb // settings.max_concurrent_jobs // 2)
    db_cpu = _positive_number(db_context.get("core_max", default_cpu), "database core_max")
    loadgen_cpu = _positive_number(loadgen_context.get("core_max", default_cpu), "loader core_max")
    db_memory_bytes = _positive_integer(
        db_context.get("mem_max", default_memory_mb * 1024 * 1024), "database mem_max"
    )
    loadgen_memory_bytes = _positive_integer(
        loadgen_context.get("mem_max", default_memory_mb * 1024 * 1024), "loader mem_max"
    )
    total_memory_mb = math.ceil((db_memory_bytes + loadgen_memory_bytes) / (1024 * 1024))
    db_environment = _environment(db_context.get("environment", {}), "database")
    loadgen_environment = _environment(loadgen_context.get("environment", {}), "loader")
    db_image = context["databaseImageName"]
    db_tmpfs_paths = options.get("dbTmpfsPaths", ["/tmp", "/var/tmp", "/run"])
    if "dbTmpfsPaths" not in options and db_image.startswith(("postgres:", "docker.io/library/postgres:")):
        tag = db_image.split("@", 1)[0].rsplit(":", 1)[-1]
        version = tag.split(".", 1)[0]
        data_path = (
            "/var/lib/postgresql"
            if version.isdigit() and int(version) >= 18
            else "/var/lib/postgresql/data"
        )
        db_tmpfs_paths.append(data_path)

    return TaskSubmission(
        id=task_id,
        db_image=db_image,
        loadgen_image=context["loaderImageName"],
        cpu_cores=db_cpu + loadgen_cpu,
        memory_mb=total_memory_mb,
        db_env=db_environment,
        loadgen_env=loadgen_environment,
        loadgen_tmpfs_paths=options.get("loadgenTmpfsPaths", ["/tmp", "/var/tmp", "/run"]),
        db_command=_command(db_context.get("command", []), "database"),
        loadgen_command=_command(loadgen_context.get("command", []), "loader"),
        db_tmpfs_paths=db_tmpfs_paths,
        timeout_seconds=options.get("timeoutSeconds", 3600),
        db_startup_grace_seconds=options.get("dbStartupGraceSeconds", 5.0),
        db_cpu_fraction=db_cpu / (db_cpu + loadgen_cpu),
        db_memory_fraction=db_memory_bytes / (db_memory_bytes + loadgen_memory_bytes),
    )


def _environment(value: Any, role: str) -> dict[str, str]:
    if not isinstance(value, dict) or any(
        not isinstance(key, str) or not isinstance(item, str)
        for key, item in value.items()
    ):
        raise ValueError(f"{role} environment must be a mapping of strings")
    return value


def _command(value: Any, role: str) -> list[str] | None:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ValueError(f"{role} command must be a list of strings")
    return value or None


def _positive_number(value: Any, field: str) -> float:
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise ValueError(f"{field} must be a finite positive number")
    return number


def _positive_integer(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{field} must be a positive integer")
    return value


def _timestamp(value: datetime) -> str:
    return value.isoformat()


def _truncate(value: str | None, max_chars: int) -> str | None:
    if value is None or len(value) <= max_chars:
        return value
    return value[-max_chars:]
