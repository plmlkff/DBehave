import asyncio
import logging
from dataclasses import dataclass

import docker

from src.config import Settings
from src.docker_runner import DockerSandboxRunner, create_docker_client, remove_stale_resources
from src.models import TaskStatus
from src.repository import TaskRecord, TaskRepository

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class ResourceUsage:
    cores: float = 0.0
    memory_mb: int = 0
    jobs: int = 0


class Worker:
    def __init__(self, settings: Settings, repository: TaskRepository) -> None:
        self.settings = settings
        self.repository = repository
        self.client: docker.DockerClient | None = None
        self.runner: DockerSandboxRunner | None = None
        self.scheduler_task: asyncio.Task[None] | None = None
        self._stop = asyncio.Event()
        self._active: dict[str, asyncio.Task[None]] = {}
        self._usage = ResourceUsage()
        self.ready = False

    async def start(self) -> None:
        self.client = await asyncio.to_thread(create_docker_client, self.settings)
        try:
            await remove_stale_resources(self.client)
            await self.repository.fail_interrupted()
            self.runner = DockerSandboxRunner(self.client, self.settings)
            self.scheduler_task = asyncio.create_task(
                self._scheduler_loop(), name="worker-scheduler"
            )
            self.ready = True
        except Exception:
            await asyncio.to_thread(self.client.close)
            self.client = None
            raise

    async def stop(self) -> None:
        self.ready = False
        self._stop.set()
        if self.scheduler_task:
            await self.scheduler_task
        active = list(self._active.values())
        for task in active:
            task.cancel()
        if active:
            await asyncio.gather(*active, return_exceptions=True)
        if self.client:
            await asyncio.to_thread(self.client.close)
            self.client = None

    async def _scheduler_loop(self) -> None:
        while not self._stop.is_set():
            try:
                pending = await self.repository.list_new()
                for record in pending:
                    if self._stop.is_set():
                        break
                    task = record.payload
                    if task.cpu_cores > self.settings.capacity_cores:
                        if await self.repository.claim(record.id):
                            await self.repository.finish(
                                record.id,
                                TaskStatus.FAILED,
                                error=(
                                    f"Requested {task.cpu_cores} CPU cores, but worker capacity is "
                                    f"{self.settings.capacity_cores}"
                                ),
                            )
                        continue
                    if task.memory_mb > self.settings.capacity_memory_mb:
                        if await self.repository.claim(record.id):
                            await self.repository.finish(
                                record.id,
                                TaskStatus.FAILED,
                                error=(
                                    f"Requested {task.memory_mb} MiB RAM, but worker capacity is "
                                    f"{self.settings.capacity_memory_mb} MiB"
                                ),
                            )
                        continue
                    if self._usage.jobs >= self.settings.max_concurrent_jobs:
                        break
                    if not self._fits(task.cpu_cores, task.memory_mb):
                        continue
                    if await self.repository.claim(record.id):
                        self._reserve(task.cpu_cores, task.memory_mb)
                        job = asyncio.create_task(
                            self._execute(record), name=f"job-{record.id}"
                        )
                        self._active[record.id] = job
                        job.add_done_callback(
                            lambda completed, task_id=record.id: self._active.pop(task_id, None)
                        )
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Scheduler iteration failed")
            try:
                await asyncio.wait_for(
                    self._stop.wait(), timeout=self.settings.scheduler_poll_seconds
                )
            except TimeoutError:
                pass

    def _fits(self, cores: float, memory_mb: int) -> bool:
        return (
            self._usage.cores + cores <= self.settings.capacity_cores + 1e-9
            and self._usage.memory_mb + memory_mb <= self.settings.capacity_memory_mb
        )

    def _reserve(self, cores: float, memory_mb: int) -> None:
        self._usage.cores += cores
        self._usage.memory_mb += memory_mb
        self._usage.jobs += 1

    def _release(self, cores: float, memory_mb: int) -> None:
        self._usage.cores = max(0.0, self._usage.cores - cores)
        self._usage.memory_mb = max(0, self._usage.memory_mb - memory_mb)
        self._usage.jobs = max(0, self._usage.jobs - 1)

    async def _execute(self, record: TaskRecord) -> None:
        assert self.runner is not None
        task = record.payload
        heartbeat_task = asyncio.create_task(self._heartbeat_loop(record.id))
        terminal_status = TaskStatus.FAILED
        exit_code: int | None = None
        error: str | None = None
        db_logs: str | None = None
        loadgen_logs: str | None = None
        try:
            result = await self.runner.run(task)
            terminal_status = TaskStatus.SUCCEEDED
            exit_code = result.exit_code
            db_logs = result.db_logs
            loadgen_logs = result.loadgen_logs
        except asyncio.CancelledError:
            error = "Worker is shutting down; sandbox was stopped"
            raise
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"
            logger.exception("Task %s failed", record.id)
        finally:
            heartbeat_task.cancel()
            await asyncio.gather(heartbeat_task, return_exceptions=True)
            # Persist the terminal state even when shutdown cancellation is in
            # flight, then release the reservation exactly once.
            persist_task = asyncio.create_task(
                self.repository.finish(
                    record.id,
                    terminal_status,
                    exit_code=exit_code,
                    error=error,
                    db_logs=db_logs,
                    loadgen_logs=loadgen_logs,
                )
            )
            try:
                await asyncio.shield(persist_task)
            except asyncio.CancelledError:
                try:
                    await persist_task
                except Exception:
                    logger.exception("Could not persist terminal state for task %s", record.id)
                raise
            except Exception:
                logger.exception("Could not persist terminal state for task %s", record.id)
            finally:
                self._release(task.cpu_cores, task.memory_mb)

    async def _heartbeat_loop(self, task_id: str) -> None:
        interval = max(1.0, self.settings.scheduler_poll_seconds * 5)
        while True:
            await asyncio.sleep(interval)
            try:
                await self.repository.heartbeat(task_id)
            except Exception:
                logger.exception("Could not update heartbeat for task %s", task_id)
