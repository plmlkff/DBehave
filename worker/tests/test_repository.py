from pydantic import SecretStr

from src.config import Settings
from src.repository import _to_submission


def make_settings(**overrides):
    values = {
        "api_token": SecretStr("x" * 32),
        "db_user": "worker",
        "db_password": SecretStr("database-password"),
        "capacity_cores": 8,
        "capacity_memory_mb": 8192,
        "max_concurrent_jobs": 4,
    }
    values.update(overrides)
    return Settings(**values)


def test_backend_execution_context_maps_images_resources_and_commands():
    context = {
        "loaderImageName": "dbehave/postgres-load:0.1.0",
        "databaseImageName": "postgres:18",
        "loaderImageContext": {
            "environment": {"SCENARIO": "mixed", "DB_HOST": "database"},
            "command": [],
            "core_max": 0.5,
            "mem_max": 536870912,
        },
        "databaseImageContext": {
            "environment": {"POSTGRES_DB": "dbehave"},
            "command": ["docker-entrypoint.sh", "postgres", "-c", "shared_buffers=128MB"],
            "core_max": 2,
            "mem_max": 1073741824,
        },
    }

    task = _to_submission(
        context,
        make_settings(),
        "00000000-0000-4000-8000-000000000001",
    )

    assert task.id == "00000000-0000-4000-8000-000000000001"
    assert task.db_image == "postgres:18"
    assert task.loadgen_image == "dbehave/postgres-load:0.1.0"
    assert task.db_command == ["docker-entrypoint.sh", "postgres", "-c", "shared_buffers=128MB"]
    assert task.loadgen_command is None
    assert task.db_cpu_fraction == 0.8
    assert task.memory_mb == 1536
    assert task.db_memory_fraction == 2 / 3
    assert task.db_data_path == "/var/lib/postgresql"
    assert "/var/lib/postgresql" not in task.db_tmpfs_paths
    assert task.loadgen_env["DB_HOST"] == "database"


def test_missing_context_limits_use_worker_capacity_share():
    context = {
        "loaderImageName": "example/loadgen:1",
        "databaseImageName": "example/database:1",
        "loaderImageContext": {"environment": {}, "command": []},
        "databaseImageContext": {"environment": {}, "command": []},
    }

    task = _to_submission(
        context,
        make_settings(capacity_cores=8, capacity_memory_mb=8192, max_concurrent_jobs=4),
        "00000000-0000-4000-8000-000000000002",
    )

    assert task.cpu_cores == 2
    assert task.memory_mb == 2048

def test_postgres_data_can_stay_in_tmpfs():
    context = {
        "loaderImageName": "dbehave/postgres-load:0.1.0",
        "databaseImageName": "postgres:16",
        "loaderImageContext": {"environment": {}, "command": []},
        "databaseImageContext": {"environment": {}, "command": []},
    }

    task = _to_submission(
        context,
        make_settings(db_data_storage="tmpfs"),
        "00000000-0000-4000-8000-000000000003",
    )

    assert task.db_data_path is None
    assert "/var/lib/postgresql/data" in task.db_tmpfs_paths
