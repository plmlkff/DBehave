from contextlib import nullcontext
from io import BytesIO
from types import SimpleNamespace
from unittest.mock import Mock

from pydantic import SecretStr

from src.config import Settings
from src.docker_runner import DockerSandboxRunner


def test_loadgen_metrics_are_pushed_with_run_id_grouping(monkeypatch):
    settings = Settings(
        api_token=SecretStr("x" * 32),
        db_user="worker",
        db_password=SecretStr("password"),
        prometheus_pushgateway_url="http://pushgateway:9091",
    )
    runner = DockerSandboxRunner(Mock(), settings)
    container = SimpleNamespace(
        attrs={"NetworkSettings": {"Networks": {"task": {"IPAddress": "172.20.0.4"}}}}
    )
    opener = Mock()
    opener.open.side_effect = [
        nullcontext(BytesIO(b"dbehave_phase{phase=\"LOAD\"} 1\n")),
        nullcontext(SimpleNamespace(status=202)),
    ]
    monkeypatch.setattr("src.docker_runner.build_opener", lambda *_: opener)

    runner._push_loadgen_metrics(
        container,
        8080,
        "00000000-0000-4000-8000-000000000001",
    )

    request = opener.open.call_args_list[1].args[0]
    assert request.full_url == (
        "http://pushgateway:9091/metrics/job/dbehave/run_id/"
        "00000000-0000-4000-8000-000000000001"
    )
    assert request.get_method() == "PUT"
    assert request.data == b"dbehave_phase{phase=\"LOAD\"} 1\n"