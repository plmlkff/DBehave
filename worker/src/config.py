from functools import lru_cache
import math
import socket
from typing import Self

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="WORKER_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    api_token: SecretStr
    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "dbehave"
    db_user: str = "dbehave"
    db_password: SecretStr = SecretStr("dbehave-local")
    instance_id: str = Field(default_factory=socket.gethostname)

    capacity_cores: float = 8.0
    capacity_memory_mb: int = 16_384
    max_concurrent_jobs: int = 4
    scheduler_poll_seconds: float = 1.0
    metrics_scrape_seconds: float = 2.0
    prometheus_pushgateway_url: str | None = None
    container_name: str | None = None

    allowed_image_prefixes: str = "docker.io/library/"

    tmpfs_size_mb: int = 256
    pids_limit: int = 256
    log_max_size: str = "10m"
    log_max_files: int = 3

    db_startup_timeout_seconds: int = 120
    max_task_timeout_seconds: int = 86_400

    require_rootless_docker: bool = False
    docker_base_url: str | None = None

    @model_validator(mode="after")
    def credentials_are_required(self) -> Self:
        if not self.db_user.strip():
            raise ValueError("WORKER_DB_USER is required")
        if not self.db_password.get_secret_value():
            raise ValueError("WORKER_DB_PASSWORD is required")
        if not self.instance_id.strip():
            raise ValueError("WORKER_INSTANCE_ID must not be empty")
        return self

    @field_validator("api_token")
    @classmethod
    def token_must_be_strong(cls, value: SecretStr) -> SecretStr:
        if len(value.get_secret_value()) < 32:
            raise ValueError("WORKER_API_TOKEN must contain at least 32 characters")
        return value

    @field_validator("capacity_cores")
    @classmethod
    def positive_cores(cls, value: float) -> float:
        if not math.isfinite(value) or value <= 0:
            raise ValueError("WORKER_CAPACITY_CORES must be a finite positive number")
        return value

    @field_validator("scheduler_poll_seconds")
    @classmethod
    def positive_poll_interval(cls, value: float) -> float:
        if not math.isfinite(value) or value <= 0:
            raise ValueError("WORKER_SCHEDULER_POLL_SECONDS must be positive")
        return value

    @field_validator("metrics_scrape_seconds")
    @classmethod
    def positive_metrics_interval(cls, value: float) -> float:
        if not math.isfinite(value) or value <= 0:
            raise ValueError("WORKER_METRICS_SCRAPE_SECONDS must be positive")
        return value

    @field_validator("db_port")
    @classmethod
    def valid_db_port(cls, value: int) -> int:
        if not 1 <= value <= 65535:
            raise ValueError("WORKER_DB_PORT must be between 1 and 65535")
        return value

    @field_validator("capacity_memory_mb", "max_concurrent_jobs", "tmpfs_size_mb", "pids_limit")
    @classmethod
    def positive_ints(cls, value: int) -> int:
        if value <= 0:
            raise ValueError("Worker capacity and limits must be positive")
        return value

    @property
    def image_prefixes(self) -> tuple[str, ...]:
        return tuple(
            item.strip().lower().rstrip("/") + "/"
            for item in self.allowed_image_prefixes.split(",")
            if item.strip()
        )

@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
