import re
from enum import StrEnum
from pathlib import PurePosixPath
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class TaskStatus(StrEnum):
    QUEUED = "QUEUED"
    NEW = "NEW"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


TaskId = Annotated[str, Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")]
EnvMap = dict[str, str]


def _validate_env(value: EnvMap) -> EnvMap:
    if len(value) > 64:
        raise ValueError("At most 64 environment variables are allowed per container")
    total_size = 0
    for key, item in value.items():
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError(f"Invalid environment variable name: {key!r}")
        if not isinstance(item, str):
            raise ValueError(f"Environment variable {key!r} must have a string value")
        if len(item) > 4096:
            raise ValueError(f"Environment variable {key!r} exceeds 4096 characters")
        total_size += len(key.encode()) + len(item.encode())
    if total_size > 32 * 1024:
        raise ValueError("Environment variables exceed 32 KiB per container")
    return value


def _validate_tmpfs_paths(paths: list[str]) -> list[str]:
    if len(paths) > 16:
        raise ValueError("At most 16 tmpfs paths are allowed per container")
    normalized: list[str] = []
    forbidden = ("/proc", "/sys", "/dev", "/etc", "/usr", "/bin", "/sbin", "/lib")
    for raw in paths:
        path = PurePosixPath(raw)
        if not raw.startswith("/") or raw == "/" or ".." in path.parts:
            raise ValueError(f"Invalid tmpfs path: {raw!r}")
        canonical = str(path)
        if any(canonical == root or canonical.startswith(root + "/") for root in forbidden):
            raise ValueError(f"Mounting tmpfs under {canonical!r} is not allowed")
        if canonical in normalized:
            raise ValueError(f"Duplicate tmpfs path: {canonical!r}")
        normalized.append(canonical)
    return normalized


class TaskSubmission(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: TaskId
    db_image: Annotated[str, Field(min_length=1, max_length=255)]
    loadgen_image: Annotated[str, Field(min_length=1, max_length=255)]

    # CPU and memory are the aggregate budget for BOTH containers.
    cpu_cores: Annotated[float, Field(ge=0.2, le=256)]
    memory_mb: Annotated[int, Field(ge=256, le=1_048_576)]

    db_env: EnvMap = Field(default_factory=dict)
    loadgen_env: EnvMap = Field(default_factory=dict)

    # The defaults are writable ephemeral memory-backed directories. Add the
    # database's data directory here, e.g. /var/lib/postgresql/data or /data.
    db_tmpfs_paths: list[str] = Field(default_factory=lambda: ["/tmp", "/var/tmp", "/run"])
    loadgen_tmpfs_paths: list[str] = Field(default_factory=lambda: ["/tmp", "/var/tmp", "/run"])

    # Fractions of the aggregate job budget assigned to the database container.
    db_command: list[str] | None = None
    loadgen_command: list[str] | None = None

    db_cpu_fraction: Annotated[float, Field(gt=0, lt=1)] = 0.5
    db_memory_fraction: Annotated[float, Field(gt=0, lt=1)] = 0.5

    timeout_seconds: Annotated[int, Field(ge=1, le=86_400)] = 3_600
    db_startup_grace_seconds: Annotated[float, Field(ge=0, le=120)] = 5.0
    status: TaskStatus = TaskStatus.NEW

    @field_validator("db_env", "loadgen_env")
    @classmethod
    def validate_environment(cls, value: EnvMap) -> EnvMap:
        return _validate_env(value)

    @field_validator("db_command", "loadgen_command")
    @classmethod
    def validate_commands(cls, value: list[str] | None) -> list[str] | None:
        if value is not None and (
            len(value) > 128 or any(len(argument) > 4096 for argument in value)
        ):
            raise ValueError("Container command exceeds the allowed size")
        return value

    @field_validator("db_tmpfs_paths", "loadgen_tmpfs_paths")
    @classmethod
    def validate_tmpfs(cls, value: list[str]) -> list[str]:
        return _validate_tmpfs_paths(value)

    @field_validator("db_image", "loadgen_image")
    @classmethod
    def validate_image_reference_syntax(cls, value: str) -> str:
        # Deliberately stricter than Docker's full reference grammar. Docker SDK
        # receives this value as an API argument (never through a shell).
        if value != value.strip() or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}", value):
            raise ValueError("Invalid Docker image reference")
        if ".." in value or "//" in value or value.endswith(("/", ":", "@")):
            raise ValueError("Invalid Docker image reference")
        return value

    @model_validator(mode="after")
    def status_must_be_new(self) -> "TaskSubmission":
        if self.status != TaskStatus.NEW:
            raise ValueError("New jobs must have status NEW")
        if self.cpu_cores * self.db_cpu_fraction < 0.1:
            raise ValueError("Database CPU allocation must be at least 0.1 core")
        if self.cpu_cores * (1 - self.db_cpu_fraction) < 0.1:
            raise ValueError("Load-generator CPU allocation must be at least 0.1 core")
        db_mem = int(self.memory_mb * self.db_memory_fraction)
        loadgen_mem = self.memory_mb - db_mem
        if min(db_mem, loadgen_mem) < 128:
            raise ValueError("At least 128 MiB must be assigned to each container")
        return self


class TaskView(BaseModel):
    id: str
    status: TaskStatus
    cpu_cores: float
    memory_mb: int
    created_at: str
    updated_at: str
    started_at: str | None = None
    finished_at: str | None = None
    exit_code: int | None = None
    error: str | None = None
    db_logs: str | None = None
    loadgen_logs: str | None = None


class HealthView(BaseModel):
    status: str
    scheduler_running: bool
