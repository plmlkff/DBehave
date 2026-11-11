import asyncio
from unittest.mock import Mock

from pydantic import SecretStr

from src.config import Settings
from src.docker_runner import DockerSandboxRunner


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
