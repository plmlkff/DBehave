import pytest
from pydantic import ValidationError

from src.image_refs import normalize_image_ref
from src.models import TaskSubmission


def base_task(**overrides):
    value = {
        "id": "test-001",
        "db_image": "postgres:16",
        "loadgen_image": "ghcr.io/example/loadgen:1.0",
        "cpu_cores": 2,
        "memory_mb": 2048,
    }
    value.update(overrides)
    return value


def test_minimal_task_is_new_and_defaults_are_ephemeral_paths():
    task = TaskSubmission.model_validate(base_task())
    assert task.status.value == "NEW"
    assert "/tmp" in task.db_tmpfs_paths
    assert task.cpu_cores == 2


def test_only_new_tasks_can_be_submitted():
    with pytest.raises(ValidationError):
        TaskSubmission.model_validate(base_task(status="RUNNING"))


def test_cpu_and_memory_are_split_and_each_container_gets_minimum():
    task = TaskSubmission.model_validate(
        base_task(cpu_cores=1, memory_mb=256, db_cpu_fraction=0.5, db_memory_fraction=0.5)
    )
    assert task.cpu_cores * task.db_cpu_fraction == 0.5
    assert task.memory_mb * task.db_memory_fraction == 128


def test_rejects_host_sensitive_tmpfs_mountpoints():
    with pytest.raises(ValidationError):
        TaskSubmission.model_validate(base_task(db_tmpfs_paths=["/proc/1/root"]))


def test_rejects_invalid_env_key():
    with pytest.raises(ValidationError):
        TaskSubmission.model_validate(base_task(loadgen_env={"NOT-VALID": "x"}))


def test_rejects_unbounded_or_host_path_mounts():
    with pytest.raises(ValidationError):
        TaskSubmission.model_validate(base_task(db_tmpfs_paths=["/../../host"]))


def test_image_reference_normalization():
    assert normalize_image_ref("postgres:16") == "docker.io/library/postgres:16"
    assert normalize_image_ref("my-org/test:dev") == "docker.io/my-org/test:dev"
    assert normalize_image_ref("ghcr.io/acme/test:dev") == "ghcr.io/acme/test:dev"
