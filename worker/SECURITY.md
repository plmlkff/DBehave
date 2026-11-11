# Security model and operational checklist

## Intended protection

The worker treats each load-test job as a separate sandbox:

- one dedicated internal bridge network per job; exactly two containers are attached;
- no published ports, host network, host PID namespace, host devices or Docker socket;
- no host bind mounts; after container creation, the worker inspects every mount and accepts only `tmpfs` mounts;
- read-only image root filesystem; user-writable locations must be explicit memory-backed tmpfs mounts;
- CPU quota, hard memory limit, memory+swap limit, PID limit and bounded Docker logs;
- drop all capabilities by default; the database container gets only a small fixed capability set needed by common DB entrypoints;
- no privilege escalation, private cgroup and IPC namespaces, no container restart policy;
- only image references matching a configured allow-list are accepted.

The worker also reserves each job's aggregate CPU and RAM budget before scheduling it. The configured worker capacity must not exceed the Docker daemon's reported physical CPU/RAM. The host must still retain spare resources for the OS, Docker daemon, registry pulls and the worker process.

## Limits and non-goals

Docker containers share the host kernel. `cap_drop`, seccomp defaults, read-only filesystems, namespaces and cgroups reduce attack surface; they do not prove that a malicious container can never exploit a kernel, Docker Engine, runtime or networking vulnerability. A Docker daemon with root privileges remains a high-value trust boundary.

For arbitrary/untrusted images, use a dedicated disposable VM or a separate worker node. Prefer rootless Docker, keep the kernel and Docker Engine patched, and pin images by digest from a private registry. Do not put secrets or unrelated workloads on the worker host.

An `internal=true` network prevents normal external egress and isolates different job networks, but a Docker bridge network is not a VM boundary from every host service. Bind the worker API to loopback or a protected management network; run the worker in a dedicated VM and firewall host services when host-network reachability is in scope.

CPU/RAM limits do not include Docker daemon/kernel overhead. Per-job disk writes are restricted to memory-backed tmpfs and bounded container logs; pulled image layers and Docker's image cache still consume host disk outside a task's cgroup. Manage the image cache and registry allow-list operationally.

The shared PostgreSQL `worker.tasks.context` stores container environment variables, which may include database passwords. Restrict PostgreSQL credentials and network access to the backend and worker; protect database backups and logs. Container environment values may also be visible to users with Docker daemon access.

## Deployment checklist

- [ ] Run the worker on a dedicated, patched Linux VM/node.
- [ ] Enable rootless Docker and set `WORKER_REQUIRE_ROOTLESS_DOCKER=true`.
- [ ] Configure `WORKER_ALLOWED_IMAGE_PREFIXES` narrowly; pin image digests for production.
- [ ] Set worker CPU/RAM capacity below the Docker VM's actual resources, leaving headroom.
- [ ] Restrict access to the bearer token; use TLS and firewall rules if the API is remotely reachable.
- [ ] Restrict access to the PostgreSQL database and protect its backups.
- [ ] Add the DB data directory and every other required writable path to the task's tmpfs list.
- [ ] Monitor Docker image cache and host disk. Do not treat image pulling as part of the per-task disk budget.
- [ ] Test the exact database and load-generator images with read-only rootfs and the fixed capability profile before production use.
