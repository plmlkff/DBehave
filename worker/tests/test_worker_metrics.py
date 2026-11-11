from contextlib import nullcontext
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock

import pytest
from pydantic import SecretStr

from src.config import Settings
from src.docker_runner import DockerSandboxRunner
from src.metrics import (
    WorkerMetricsPublisher,
    delete_metrics_group,
    push_metrics,
    render_task_container_metrics,
    render_worker_metrics,
    task_metrics_group,
)
from src.scheduler import Worker


def test_worker_metrics_include_process_disk_and_reserved_capacity():
    payload = render_worker_metrics(
        process_cpu_seconds=2.5,
        process_memory_bytes=1024,
        filesystem_total_bytes=10_000,
        filesystem_used_bytes=4_000,
        filesystem_free_bytes=6_000,
        active_jobs=2,
        reserved_cpu_cores=1.5,
        reserved_memory_bytes=3_000,
        docker_images_bytes=100,
        docker_containers_bytes=200,
        docker_volumes_bytes=300,
    ).decode()

    assert "dbehave_worker_process_cpu_seconds_total 2.5\n" in payload
    assert "dbehave_worker_process_memory_rss_bytes 1024\n" in payload
    assert "dbehave_worker_filesystem_free_bytes 6000\n" in payload
    assert "dbehave_worker_active_jobs 2\n" in payload
    assert "dbehave_worker_reserved_cpu_cores 1.5\n" in payload
    assert "dbehave_worker_docker_images_bytes 100\n" in payload
    assert "dbehave_worker_docker_containers_bytes 200\n" in payload
    assert "dbehave_worker_docker_volumes_bytes 300\n" in payload


def test_task_container_metrics_normalize_memory_and_include_block_io():
    payload = render_task_container_metrics(
        {
            "database": {
                "cpu_stats": {"cpu_usage": {"total_usage": 1_500_000_000}},
                "memory_stats": {
                    "usage": 1_000,
                    "limit": 4_096,
                    "stats": {"inactive_file": 250},
                },
                "blkio_stats": {
                    "io_service_bytes_recursive": [
                        {"op": "Read", "value": 500},
                        {"op": "Write", "value": 200},
                        {"op": "Total", "value": 700},
                    ]
                },
                "pids_stats": {"current": 3},
            }
        }
    ).decode()

    assert (
        'dbehave_worker_container_cpu_seconds_total{role="database"} 1.5\n'
        in payload
    )
    assert 'dbehave_worker_container_memory_bytes{role="database"} 750\n' in payload
    assert (
        'dbehave_worker_container_memory_limit_bytes{role="database"} 4096\n'
        in payload
    )
    assert (
        'dbehave_worker_container_block_io_read_bytes_total{role="database"} 500\n'
        in payload
    )
    assert (
        'dbehave_worker_container_block_io_write_bytes_total{role="database"} 200\n'
        in payload
    )
    assert 'dbehave_worker_container_pids{role="database"} 3\n' in payload


def test_worker_publisher_collects_process_disk_and_docker_usage(monkeypatch):
    settings = Settings(
        api_token=SecretStr("x" * 32),
        db_user="worker",
        db_password=SecretStr("password"),
        instance_id="worker-1",
        prometheus_pushgateway_url="http://pushgateway:9091",
    )
    process = Mock()
    process.cpu_times.return_value = SimpleNamespace(user=1.25, system=0.75)
    process.memory_info.return_value = SimpleNamespace(rss=2_048)
    monkeypatch.setattr("src.metrics.psutil.Process", lambda: process)
    monkeypatch.setattr(
        "src.metrics.psutil.disk_usage",
        lambda _: SimpleNamespace(total=10_000, used=4_000, free=6_000),
    )
    monkeypatch.setattr("src.metrics.time.monotonic", lambda: 100.0)
    client = Mock()
    client.df.return_value = {
        "Images": [{"Size": 100}, {"Size": 200}],
        "Containers": [{"SizeRw": 300}],
        "Volumes": [{"UsageData": {"Size": 400}}, {"UsageData": {"Size": -1}}],
    }

    publisher = WorkerMetricsPublisher(settings, client)
    payload = publisher._collect(
        active_jobs=1,
        reserved_cpu_cores=2.0,
        reserved_memory_bytes=8192,
    ).decode()

    assert "dbehave_worker_process_cpu_seconds_total 2.0\n" in payload
    assert "dbehave_worker_process_memory_rss_bytes 2048\n" in payload
    assert "dbehave_worker_filesystem_used_bytes 4000\n" in payload
    assert "dbehave_worker_docker_images_bytes 300\n" in payload
    assert "dbehave_worker_docker_containers_bytes 300\n" in payload
    assert "dbehave_worker_docker_volumes_bytes 400\n" in payload
    client.df.assert_called_once_with()
    assert task_metrics_group("worker-1", "run/one") == {
        "instance": "worker-1",
        "run_id": "run/one",
    }


def test_push_metrics_uses_put_and_encoded_group_labels(monkeypatch):
    opener = MagicMock()
    response = Mock(status=202)
    opener.open.return_value = nullcontext(response)
    monkeypatch.setattr("src.metrics.build_opener", lambda *_: opener)

    push_metrics(
        "http://pushgateway:9091/",
        "dbehave_worker_task",
        {"instance": "worker-1", "run_id": "run/one"},
        b"metric 1\n",
    )

    request = opener.open.call_args.args[0]
    assert request.full_url == (
        "http://pushgateway:9091/metrics/job/dbehave_worker_task/"
        "instance/worker-1/run_id/run%2Fone"
    )
    assert request.get_method() == "PUT"
    assert request.data == b"metric 1\n"


def test_delete_metrics_group_uses_instance_and_run_id(monkeypatch):
    opener = MagicMock()
    opener.open.return_value = nullcontext(Mock(status=202))
    monkeypatch.setattr("src.metrics.build_opener", lambda *_: opener)

    delete_metrics_group(
        "http://pushgateway:9091",
        "dbehave_worker_task",
        task_metrics_group("worker-1", "run-1"),
    )

    request = opener.open.call_args.args[0]
    assert request.full_url == (
        "http://pushgateway:9091/metrics/job/dbehave_worker_task/"
        "instance/worker-1/run_id/run-1"
    )
    assert request.get_method() == "DELETE"


@pytest.mark.asyncio
async def test_runner_forwards_plugin_independent_container_metrics(monkeypatch):
    settings = Settings(
        api_token=SecretStr("x" * 32),
        db_user="worker",
        db_password=SecretStr("password"),
        instance_id="worker-1",
        prometheus_pushgateway_url="http://pushgateway:9091",
    )
    runner = DockerSandboxRunner(Mock(), settings)
    stats = {
        "cpu_stats": {"cpu_usage": {"total_usage": 2_000_000_000}},
        "memory_stats": {"usage": 3_000, "limit": 4_000, "stats": {}},
        "blkio_stats": {"io_service_bytes_recursive": []},
        "pids_stats": {"current": 4},
    }
    db_container = Mock()
    db_container.stats.return_value = stats
    loadgen_container = Mock()
    loadgen_container.stats.return_value = stats
    pushed = []

    def capture_push(url, job, grouping, payload):
        pushed.append((url, job, grouping, payload))

    monkeypatch.setattr("src.docker_runner.push_metrics", capture_push)

    await runner._forward_task_metrics(
        db_container,
        loadgen_container,
        "00000000-0000-4000-8000-000000000001",
    )

    assert len(pushed) == 1
    _, job, grouping, payload = pushed[0]
    assert job == "dbehave_worker_task"
    assert grouping == {
        "instance": "worker-1",
        "run_id": "00000000-0000-4000-8000-000000000001",
    }
    assert b'role="database"' in payload
    assert b'role="loadgen"' in payload


@pytest.mark.asyncio
async def test_worker_metrics_loop_publishes_scheduler_reservations():
    settings = Settings(
        api_token=SecretStr("x" * 32),
        db_user="worker",
        db_password=SecretStr("password"),
        metrics_scrape_seconds=0.01,
    )
    worker = Worker(settings, Mock())
    worker._usage.cores = 1.5
    worker._usage.memory_mb = 256
    worker._usage.jobs = 2
    published = []

    class Publisher:
        async def publish(self, **metrics):
            published.append(metrics)
            worker._stop.set()

    worker.metrics_publisher = Publisher()
    await worker._metrics_loop()

    assert published == [
        {
            "active_jobs": 2,
            "reserved_cpu_cores": 1.5,
            "reserved_memory_bytes": 256 * 1024 * 1024,
        }
    ]
