import asyncio
from unittest.mock import Mock

import pytest

from pydantic import SecretStr

from src.config import Settings
from src.docker_runner import DockerSandboxRunner, SandboxError


def test_container_creation_uses_requested_network():
    network = "db-load-worker-test"
    endpoint_config = {"Aliases": ["db", "database"]}
    client = Mock()
    client.api.create_endpoint_config.return_value = endpoint_config
    client.api.create_networking_config.side_effect = lambda config: config
    container = Mock(
        attrs={
            "HostConfig": {
                "NanoCpus": 1_000_000_000,
                "Memory": 512 * 1024 * 1024,
                "MemorySwap": 512 * 1024 * 1024,
                "PidsLimit": 256,
                "ReadonlyRootfs": True,
                "Privileged": False,
                "CapDrop": ["ALL"],
                "SecurityOpt": ["no-new-privileges:true"],
            },
            "NetworkSettings": {"Networks": {network: {}}},
            "Mounts": [],
        }
    )
    client.containers.create.return_value = container
    runner = DockerSandboxRunner(
        client,
        Settings(
            api_token=SecretStr("x" * 32),
            db_user="worker",
            db_password=SecretStr("password"),
        ),
    )

    asyncio.run(
        runner._create_container(
            image="postgres:16",
            name="test-db",
            environment={},
            network=network,
            alias="db",
            cpu_cores=1,
            memory_bytes=512 * 1024 * 1024,
            tmpfs_paths=[],
            command=None,
            labels={},
        )
    )

    create_kwargs = client.containers.create.call_args.kwargs
    assert create_kwargs["network"] == network
    assert create_kwargs["hostname"] == "database"
    assert create_kwargs["networking_config"] == {network: endpoint_config}
    client.api.create_endpoint_config.assert_called_once_with(
        aliases=["db", "database"]
    )


def make_runner(mounts):
    network = "db-load-worker-test"
    client = Mock()
    client.api.create_endpoint_config.return_value = {}
    client.api.create_networking_config.side_effect = lambda config: config
    container = Mock(
        attrs={
            "HostConfig": {
                "NanoCpus": 1_000_000_000,
                "Memory": 512 * 1024 * 1024,
                "MemorySwap": 512 * 1024 * 1024,
                "PidsLimit": 256,
                "ReadonlyRootfs": True,
                "Privileged": False,
                "CapDrop": ["ALL"],
                "SecurityOpt": ["no-new-privileges:true"],
            },
            "NetworkSettings": {"Networks": {network: {}}},
            "Mounts": mounts,
        }
    )
    client.containers.create.return_value = container
    runner = DockerSandboxRunner(
        client,
        Settings(api_token=SecretStr("x" * 32), db_user="worker", db_password=SecretStr("password")),
    )
    return runner, client, network


def create_db(runner, network, data_volume):
    return asyncio.run(
        runner._create_container(
            image="postgres:18",
            name="test-db",
            environment={},
            network=network,
            alias="db",
            cpu_cores=1,
            memory_bytes=512 * 1024 * 1024,
            tmpfs_paths=["/tmp"],
            command=None,
            labels={},
            data_volume=data_volume,
        )
    )


def test_task_data_volume_is_mounted_and_allowed():
    mounts = [{"Type": "volume", "Name": "task-data", "Destination": "/var/lib/postgresql"}]
    runner, client, network = make_runner(mounts)

    create_db(runner, network, ("task-data", "/var/lib/postgresql"))

    create_kwargs = client.containers.create.call_args.kwargs
    mount = create_kwargs["mounts"][0]
    assert (mount["Type"], mount["Source"], mount["Target"]) == ("volume", "task-data", "/var/lib/postgresql")
    assert create_kwargs["read_only"] is True


def test_foreign_volume_is_rejected():
    mounts = [{"Type": "volume", "Name": "anonymous", "Destination": "/var/lib/postgresql"}]
    runner, client, network = make_runner(mounts)

    with pytest.raises(SandboxError, match="non-tmpfs mount"):
        create_db(runner, network, ("task-data", "/var/lib/postgresql"))
