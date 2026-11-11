import asyncio
import hashlib
import json
import logging
import time
from dataclasses import dataclass
from typing import Any
from urllib.error import URLError
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener

import docker
from docker.errors import APIError, DockerException, ImageNotFound, NotFound
from docker.types import LogConfig

from src.config import Settings
from src.models import TaskSubmission
from src.image_refs import normalize_image_ref
from src.metrics import (
    delete_metrics_group,
    push_metrics,
    render_task_container_metrics,
    task_metrics_group,
)

logger = logging.getLogger(__name__)
MANAGED_LABEL = "db-load-worker.managed"
APP_LABEL = "db-load-worker.app"
TASK_LABEL = "db-load-worker.task"


class SandboxError(RuntimeError):
    pass


@dataclass(slots=True)
class SandboxResult:
    exit_code: int | None
    db_logs: str
    loadgen_logs: str


class DockerSandboxRunner:
    """Creates and tears down one isolated network + two resource-limited containers."""

    def __init__(self, client: docker.DockerClient, settings: Settings) -> None:
        self.client = client
        self.settings = settings

    async def run(self, task: TaskSubmission) -> SandboxResult:
        self._check_image_allowed(task.db_image)
        self._check_image_allowed(task.loadgen_image)

        digest = hashlib.sha256(task.id.encode("utf-8")).hexdigest()[:16]
        labels = {MANAGED_LABEL: "true", APP_LABEL: "worker", TASK_LABEL: digest}
        network = None
        worker_container = None
        db_container = None
        loadgen_container = None
        exit_code: int | None = None
        error: BaseException | None = None
        db_logs = ""
        loadgen_logs = ""
        cleanup_errors: list[str] = []

        try:
            await self._pull_image(task.db_image)
            await self._pull_image(task.loadgen_image)

            network = await asyncio.to_thread(
                self.client.networks.create,
                name=f"db-load-worker-{digest}",
                driver="bridge",
                internal=True,
                attachable=False,
                enable_ipv6=False,
                labels=labels,
            )
            await asyncio.to_thread(network.reload)
            if network.attrs.get("Internal") is not True or network.attrs.get("Driver") != "bridge":
                raise SandboxError("Docker did not create the requested internal bridge network")
            if self.settings.container_name:
                worker_container = await asyncio.to_thread(
                    self.client.containers.get, self.settings.container_name
                )
                await asyncio.to_thread(
                    network.connect, worker_container, aliases=["worker"]
                )

            db_cpu = task.cpu_cores * task.db_cpu_fraction
            loadgen_cpu = task.cpu_cores - db_cpu
            total_memory = task.memory_mb * 1024 * 1024
            db_memory = int(total_memory * task.db_memory_fraction)
            loadgen_memory = total_memory - db_memory

            db_container = await self._create_container(
                image=task.db_image,
                name=f"db-load-worker-{digest}-db",
                environment=task.db_env,
                network=network.name,
                alias="db",
                cpu_cores=db_cpu,
                memory_bytes=db_memory,
                tmpfs_paths=task.db_tmpfs_paths,
                command=task.db_command,
                labels=labels,
            )
            await asyncio.to_thread(db_container.start)
            await self._wait_for_db(db_container, task.db_startup_grace_seconds)

            loadgen_container = await self._create_container(
                image=task.loadgen_image,
                name=f"db-load-worker-{digest}-loadgen",
                environment={
                    **task.loadgen_env,
                    "DB_HOST": task.loadgen_env.get("DB_HOST", "database"),
                },
                network=network.name,
                alias="loadgen",
                cpu_cores=loadgen_cpu,
                memory_bytes=loadgen_memory,
                tmpfs_paths=task.loadgen_tmpfs_paths,
                command=task.loadgen_command,
                labels=labels,
            )
            await asyncio.to_thread(loadgen_container.start)
            health_port = None
            if "SCENARIO" in task.loadgen_env:
                try:
                    health_port = int(task.loadgen_env.get("HTTP_PORT", "8080"))
                except ValueError as exc:
                    raise SandboxError("HTTP_PORT must be an integer") from exc
                if not 1 <= health_port <= 65535:
                    raise SandboxError("HTTP_PORT must be between 1 and 65535")
            exit_code = await self._wait_for_loadgen(
                db_container, loadgen_container, task.timeout_seconds, health_port, task.id
            )
            if exit_code != 0:
                raise SandboxError(f"Load-generator container exited with code {exit_code}")
        except BaseException as exc:
            error = exc
        finally:
            # Shield cleanup so API shutdown/cancellation does not leave named
            # containers or networks running on the host.
            cleanup_task = asyncio.create_task(
                self._cleanup(db_container, loadgen_container, network, worker_container)
            )
            try:
                db_logs, loadgen_logs, cleanup_errors = await asyncio.shield(cleanup_task)
            except asyncio.CancelledError:
                # Complete cleanup even when the caller is being cancelled.
                try:
                    db_logs, loadgen_logs, cleanup_errors = await cleanup_task
                except Exception:
                    logger.exception("Sandbox cleanup failed for task %s", task.id)
                raise
            except Exception as exc:
                cleanup_errors.append(f"cleanup failed: {type(exc).__name__}: {exc}")
                logger.exception("Sandbox cleanup failed for task %s", task.id)
            if self.settings.prometheus_pushgateway_url:
                try:
                    await asyncio.to_thread(
                        delete_metrics_group,
                        self.settings.prometheus_pushgateway_url,
                        "dbehave_worker_task",
                        task_metrics_group(self.settings.instance_id, task.id),
                    )
                except Exception:
                    logger.warning(
                        "Could not remove task resource metrics for %s",
                        task.id,
                        exc_info=True,
                    )

        if isinstance(error, asyncio.CancelledError):
            if cleanup_errors:
                logger.error("Sandbox cleanup incomplete after cancellation of task %s: %s", task.id, cleanup_errors)
            raise error
        if cleanup_errors:
            cleanup_message = "; ".join(cleanup_errors)
            if error is None:
                error = SandboxError("Load test ended, but sandbox cleanup was incomplete")
            error = SandboxError(f"{error}; cleanup errors: {cleanup_message}")
        if error is not None:
            if isinstance(error, SandboxError):
                raise error
            raise SandboxError(f"Sandbox failed: {type(error).__name__}: {error}") from error

        return SandboxResult(exit_code=exit_code, db_logs=db_logs, loadgen_logs=loadgen_logs)

    def _check_image_allowed(self, image_ref: str) -> None:
        normalized = normalize_image_ref(image_ref)
        prefixes = self.settings.image_prefixes
        if not prefixes:
            raise SandboxError("No allowed image prefixes configured; refusing to run images")
        if not any(normalized.startswith(prefix) for prefix in prefixes):
            raise SandboxError(
                f"Image {image_ref!r} is outside WORKER_ALLOWED_IMAGE_PREFIXES"
            )

    async def _pull_image(self, image_ref: str) -> None:
        def pull() -> None:
            try:
                self.client.images.get(image_ref)
            except ImageNotFound:
                logger.info("Pulling image %s", image_ref)
                self.client.images.pull(image_ref)
        try:
            await asyncio.to_thread(pull)
        except (APIError, DockerException) as exc:
            raise SandboxError(f"Cannot obtain image {image_ref!r}: {exc}") from exc

    async def _create_container(
        self,
        *,
        image: str,
        name: str,
        environment: dict[str, str],
        network: str,
        alias: str,
        cpu_cores: float,
        memory_bytes: int,
        tmpfs_paths: list[str],
        command: list[str] | None,
        labels: dict[str, str],
    ) -> Any:
        tmpfs = {
            path: (
                "rw,noexec,nosuid,nodev,size="
                f"{self.settings.tmpfs_size_mb}m"
            )
            for path in tmpfs_paths
        }
        log_config = LogConfig(
            type="json-file",
            config={
                "max-size": self.settings.log_max_size,
                "max-file": str(self.settings.log_max_files),
            },
        )
        container = None
        try:
            container = await asyncio.to_thread(
                self.client.containers.create,
                image=image,
                name=name,
                command=command or None,
                environment=environment,
                detach=True,
                hostname="database" if alias == "db" else alias,
                network=network,
                networking_config=self.client.api.create_networking_config({
                    network: self.client.api.create_endpoint_config(
                        aliases=[alias, "database"] if alias == "db" else [alias]
                    )
                }),
                nano_cpus=max(100_000_000, int(cpu_cores * 1_000_000_000)),
                mem_limit=memory_bytes,
                memswap_limit=memory_bytes,  # no additional swap allowance
                pids_limit=self.settings.pids_limit,
                cap_drop=["ALL"],
                # Database entrypoints commonly chown their data directory
                # and then drop privileges. These five narrowly-scoped caps
                # are only enabled for the DB container; no host paths/devices
                # are mounted, and no-new-privileges remains enabled.
                cap_add=(
                    ["CHOWN", "SETUID", "SETGID", "DAC_OVERRIDE", "FOWNER"]
                    if alias == "db" else []
                ),
                security_opt=["no-new-privileges:true"],
                read_only=True,
                tmpfs=tmpfs,
                privileged=False,
                ipc_mode="private",
                cgroupns="private",
                init=True,
                shm_size=64 * 1024 * 1024,
                log_config=log_config,
                labels=labels,
                tty=False,
                stdin_open=False,
                restart_policy={"Name": "no"},
            )
            # Images may declare VOLUME in their Dockerfile. Do not let such
            # implicit anonymous volumes write to Docker-managed host storage.
            # Every actual mount must be one of the tmpfs mounts requested above.
            await asyncio.to_thread(container.reload)
            self._audit_container(
                container,
                expected_cpu_nano=max(100_000_000, int(cpu_cores * 1_000_000_000)),
                expected_memory=memory_bytes,
                expected_network=network,
            )
            unexpected_mounts = [
                mount for mount in container.attrs.get("Mounts", [])
                if mount.get("Type") != "tmpfs"
            ]
            if unexpected_mounts:
                await asyncio.to_thread(container.remove, force=True, v=True)
                details = ", ".join(
                    f"{item.get('Type')}:{item.get('Destination')}"
                    for item in unexpected_mounts
                )
                raise SandboxError(
                    f"Image {image!r} requested a non-tmpfs mount ({details}); "
                    "add its data directory to the role's tmpfs paths"
                )
            return container
        except (APIError, DockerException) as exc:
            if container is not None:
                try:
                    await asyncio.to_thread(container.remove, force=True, v=True)
                except Exception:
                    logger.exception("Could not remove rejected container %s", name)
            raise SandboxError(f"Cannot create container {name!r}: {exc}") from exc
        except Exception:
            if container is not None:
                try:
                    await asyncio.to_thread(container.remove, force=True, v=True)
                except Exception:
                    logger.exception("Could not remove rejected container %s", name)
            raise

    def _audit_container(
        self,
        container: Any,
        *,
        expected_cpu_nano: int,
        expected_memory: int,
        expected_network: str,
    ) -> None:
        host = container.attrs.get("HostConfig", {})
        expected = {
            "NanoCpus": expected_cpu_nano,
            "Memory": expected_memory,
            "MemorySwap": expected_memory,
            "PidsLimit": self.settings.pids_limit,
        }
        mismatches = [
            f"{key}={host.get(key)!r} (expected {value!r})"
            for key, value in expected.items()
            if host.get(key) != value
        ]
        if not host.get("ReadonlyRootfs"):
            mismatches.append("ReadonlyRootfs is not enabled")
        if host.get("Privileged"):
            mismatches.append("Privileged mode is enabled")
        attached_networks = container.attrs.get("NetworkSettings", {}).get("Networks", {})
        if set(attached_networks) != {expected_network}:
            mismatches.append(
                f"attached networks={list(attached_networks)!r} "
                f"(expected only {expected_network!r})"
            )
        if host.get("PortBindings") or host.get("PublishAllPorts"):
            mismatches.append("host port publishing is configured")
        if host.get("Binds") or host.get("Devices") or host.get("DeviceRequests"):
            mismatches.append("host binds or devices are configured")
        if host.get("PidMode") == "host":
            mismatches.append("host PID namespace is enabled")
        if "ALL" not in (host.get("CapDrop") or []):
            mismatches.append("capabilities are not dropped by default")
        if not any(
            item.lower() == "no-new-privileges:true"
            for item in (host.get("SecurityOpt") or [])
        ):
            mismatches.append("no-new-privileges is not enabled")
        if mismatches:
            raise SandboxError(
                "Docker did not apply the requested container restrictions: "
                + "; ".join(mismatches)
            )

    async def _wait_for_db(self, container: Any, grace_seconds: float) -> None:
        started = time.monotonic()
        deadline = started + self.settings.db_startup_timeout_seconds
        while time.monotonic() < deadline:
            await asyncio.to_thread(container.reload)
            state = container.attrs.get("State", {})
            if state.get("Status") != "running":
                raise SandboxError(
                    f"Database container stopped during startup (status={state.get('Status')})"
                )
            health = state.get("Health")
            if health:
                health_status = health.get("Status")
                if health_status == "healthy":
                    return
                if health_status == "unhealthy":
                    raise SandboxError("Database container healthcheck reported unhealthy")
            elif time.monotonic() - started >= grace_seconds:
                return
            await asyncio.sleep(0.5)
        raise SandboxError("Database did not become healthy before startup timeout")

    async def _wait_for_loadgen(
        self,
        db_container: Any,
        loadgen_container: Any,
        timeout_seconds: int,
        health_port: int | None = None,
        task_id: str | None = None,
    ) -> int:
        deadline = time.monotonic() + timeout_seconds
        next_metrics_scrape = 0.0
        next_task_metrics_scrape = 0.0
        while time.monotonic() < deadline:
            await asyncio.to_thread(loadgen_container.reload)
            load_state = loadgen_container.attrs.get("State", {})
            load_status = load_state.get("Status")
            if load_status == "exited":
                return int(load_state.get("ExitCode", 1))
            if load_status != "running":
                raise SandboxError(
                    f"Load-generator entered unexpected state {load_status!r}"
                )
            if health_port is not None:
                health = await asyncio.to_thread(
                    self._read_loadgen_health, loadgen_container, health_port
                )
                now = time.monotonic()
                if (
                    task_id
                    and self.settings.prometheus_pushgateway_url
                    and now >= next_metrics_scrape
                ):
                    await self._forward_metrics(
                        loadgen_container, health_port, task_id, required=False
                    )
                    next_metrics_scrape = now + self.settings.metrics_scrape_seconds
                if health and health.get("status") == "DONE":
                    if task_id and self.settings.prometheus_pushgateway_url:
                        await self._forward_metrics(
                            loadgen_container, health_port, task_id, required=True
                        )
                        await self._forward_task_metrics(
                            db_container, loadgen_container, task_id
                        )
                    return 0
                if health and health.get("status") == "FAILED":
                    if task_id and self.settings.prometheus_pushgateway_url:
                        await self._forward_metrics(
                            loadgen_container, health_port, task_id, required=True
                        )
                        await self._forward_task_metrics(
                            db_container, loadgen_container, task_id
                        )
                    raise SandboxError(
                        f"Load-generator failed: {health.get('error') or 'unknown error'}"
                    )

            now = time.monotonic()
            if (
                task_id
                and self.settings.prometheus_pushgateway_url
                and now >= next_task_metrics_scrape
            ):
                await self._forward_task_metrics(
                    db_container, loadgen_container, task_id
                )
                next_task_metrics_scrape = now + self.settings.metrics_scrape_seconds

            await asyncio.to_thread(db_container.reload)
            db_state = db_container.attrs.get("State", {})
            if db_state.get("Status") != "running":
                raise SandboxError(
                    f"Database container stopped while load test was running "
                    f"(status={db_state.get('Status')})"
                )
            await asyncio.sleep(0.5)
        raise SandboxError(f"Load-generator timed out after {timeout_seconds} seconds")

    async def _forward_task_metrics(
        self,
        db_container: Any,
        loadgen_container: Any,
        task_id: str,
    ) -> None:
        try:
            samples = await asyncio.gather(
                asyncio.to_thread(db_container.stats, stream=False),
                asyncio.to_thread(loadgen_container.stats, stream=False),
            )
            metrics = render_task_container_metrics(
                {"database": samples[0], "loadgen": samples[1]}
            )
            await asyncio.to_thread(
                push_metrics,
                self.settings.prometheus_pushgateway_url or "",
                "dbehave_worker_task",
                task_metrics_group(self.settings.instance_id, task_id),
                metrics,
            )
        except Exception as exc:
            logger.warning(
                "Could not forward container resource metrics for task %s: %s",
                task_id,
                exc,
                exc_info=True,
            )

    async def _forward_metrics(
        self,
        container: Any,
        port: int,
        task_id: str,
        *,
        required: bool,
    ) -> None:
        try:
            await asyncio.to_thread(self._push_loadgen_metrics, container, port, task_id)
        except Exception as exc:
            if required:
                raise SandboxError(f"Could not forward final Prometheus metrics: {exc}") from exc
            logger.warning("Could not forward Prometheus metrics for task %s: %s", task_id, exc)

    def _push_loadgen_metrics(self, container: Any, port: int, task_id: str) -> None:
        ip_address = self._container_ip(container)
        if not ip_address:
            raise SandboxError("Load-generator has no IP address in its task network")
        opener = build_opener(ProxyHandler({}))
        with opener.open(f"http://{ip_address}:{port}/metrics", timeout=3) as response:
            metrics = response.read(2 * 1024 * 1024)
        if not metrics.strip():
            raise SandboxError("Load-generator returned an empty metrics payload")

        push_url = (
            f"{self.settings.prometheus_pushgateway_url.rstrip('/')}/metrics/job/dbehave"
            f"/run_id/{quote(task_id, safe='')}"
        )
        request = Request(
            push_url,
            data=metrics,
            headers={"Content-Type": "text/plain; version=0.0.4; charset=utf-8"},
            method="PUT",
        )
        with opener.open(request, timeout=3) as response:
            if response.status >= 300:
                raise SandboxError(f"Pushgateway returned HTTP {response.status}")

    @staticmethod
    def _container_ip(container: Any) -> str | None:
        networks = container.attrs.get("NetworkSettings", {}).get("Networks", {})
        return next(
            (network.get("IPAddress") for network in networks.values() if network.get("IPAddress")),
            None,
        )

    def _read_loadgen_health(self, container: Any, port: int) -> dict[str, Any] | None:
        ip_address = self._container_ip(container)
        if not ip_address:
            return None
        request = Request(f"http://{ip_address}:{port}/health", method="GET")
        try:
            with build_opener(ProxyHandler({})).open(request, timeout=1) as response:
                value = json.loads(response.read(16 * 1024))
            return value if isinstance(value, dict) else None
        except (OSError, URLError, TimeoutError, json.JSONDecodeError):
            return None

    async def _cleanup(
        self,
        db_container: Any | None,
        loadgen_container: Any | None,
        network: Any | None,
        worker_container: Any | None = None,
    ) -> tuple[str, str, list[str]]:
        # Stop the load generator first so it cannot continue producing work
        # while the database is being shut down.
        containers = [("loadgen", loadgen_container), ("db", db_container)]
        logs_by_role = {"db": "", "loadgen": ""}
        cleanup_errors: list[str] = []
        for role, container in containers:
            if container is None:
                continue
            try:
                await asyncio.to_thread(container.reload)
                if container.attrs.get("State", {}).get("Running"):
                    await asyncio.to_thread(container.stop, timeout=5)
            except NotFound:
                pass
            except (APIError, DockerException) as exc:
                logger.warning(
                    "Could not stop container %s cleanly before forced removal: %s",
                    getattr(container, "name", role), exc,
                )
            try:
                raw = await asyncio.to_thread(
                    container.logs, stdout=True, stderr=True, tail=200
                )
                logs_by_role[role] = raw.decode("utf-8", errors="replace")[-32768:]
            except NotFound:
                pass
            except (APIError, DockerException) as exc:
                logger.warning(
                    "Could not read logs from container %s: %s",
                    getattr(container, "name", role), exc,
                )
            try:
                await asyncio.to_thread(container.remove, force=True, v=True)
            except NotFound:
                pass
            except (APIError, DockerException) as exc:
                cleanup_errors.append(
                    f"remove {getattr(container, 'name', role)}: {exc}"
                )

        if network is not None and worker_container is not None:
            try:
                await asyncio.to_thread(network.disconnect, worker_container, force=True)
            except NotFound:
                pass
            except (APIError, DockerException) as exc:
                cleanup_errors.append(f"disconnect worker from network: {exc}")

        if network is not None:
            try:
                await asyncio.to_thread(network.remove)
            except NotFound:
                pass
            except (APIError, DockerException) as exc:
                cleanup_errors.append(
                    f"remove network {getattr(network, 'name', 'network')}: {exc}"
                )
        return logs_by_role["db"], logs_by_role["loadgen"], cleanup_errors


def create_docker_client(settings: Settings) -> docker.DockerClient:
    if settings.docker_base_url:
        client = docker.DockerClient(base_url=settings.docker_base_url, timeout=120)
    else:
        client = docker.from_env(timeout=120)
    try:
        info = client.info()
        cgroup_version = str(info.get("CgroupVersion", ""))
        if cgroup_version not in {"1", "2"}:
            raise RuntimeError(
                "Docker daemon did not report cgroup version 1 or 2; refusing to run "
                "because CPU/RAM enforcement cannot be verified"
            )
        warnings = [str(item).lower() for item in (info.get("Warnings") or [])]
        unsupported = [
            item for item in warnings
            if any(token in item for token in (
                "no memory limit support",
                "no swap limit support",
                "no cpu cfs quota support",
                "no cpu cfs period support",
            ))
        ]
        if unsupported:
            raise RuntimeError(
                "Docker host does not support required cgroup limits: " + "; ".join(unsupported)
            )
        physical_cores = int(info.get("NCPU") or 0)
        physical_memory = int(info.get("MemTotal") or 0)
        if physical_cores and settings.capacity_cores > physical_cores:
            raise RuntimeError(
                f"WORKER_CAPACITY_CORES ({settings.capacity_cores}) exceeds Docker host CPU count "
                f"({physical_cores})"
            )
        if physical_memory and settings.capacity_memory_mb * 1024 * 1024 > physical_memory:
            raise RuntimeError(
                "WORKER_CAPACITY_MEMORY_MB exceeds Docker host physical memory; "
                "leave additional capacity for the OS and Docker daemon"
            )
        security_options = [str(item).lower() for item in info.get("SecurityOptions", [])]
        rootless = any("rootless" in item for item in security_options)
        if settings.require_rootless_docker and not rootless:
            raise RuntimeError(
                "WORKER_REQUIRE_ROOTLESS_DOCKER=true but the Docker daemon is not rootless"
            )
        if not rootless:
            logger.warning(
                "Docker daemon is not rootless. Container hardening is enabled, "
                "but the daemon still has host-level privileges; use rootless Docker or a VM."
            )
        return client
    except Exception:
        client.close()
        raise


async def remove_stale_resources(client: docker.DockerClient) -> None:
    """Remove resources left by a crash. Intended for a single worker instance."""
    containers = await asyncio.to_thread(
        client.containers.list, all=True, filters={"label": [f"{MANAGED_LABEL}=true", f"{APP_LABEL}=worker"]}
    )
    for container in containers:
        try:
            await asyncio.to_thread(container.remove, force=True, v=True)
        except (APIError, DockerException, NotFound):
            logger.exception("Could not remove stale container %s", container.name)

    networks = await asyncio.to_thread(
        client.networks.list, filters={"label": [f"{MANAGED_LABEL}=true", f"{APP_LABEL}=worker"]}
    )
    for network in networks:
        try:
            await asyncio.to_thread(network.remove)
        except (APIError, DockerException, NotFound):
            logger.exception("Could not remove stale network %s", network.name)
