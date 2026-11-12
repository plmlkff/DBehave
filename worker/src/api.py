import secrets
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from src.config import Settings
from src.models import HealthView, TaskStatus, TaskRequest, TaskView
from src.repository import TaskRepository
from src.scheduler import Worker


bearer = HTTPBearer(auto_error=False)


def create_app(settings: Settings) -> FastAPI:
    repository = TaskRepository(settings)
    worker = Worker(settings, repository)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        try:
            await repository.initialize()
            await worker.start()
            app.state.worker = worker
            app.state.repository = repository
            yield
        finally:
            try:
                await worker.stop()
            finally:
                await repository.close()

    app = FastAPI(
        title="Database Load Worker",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    async def authenticate(
        credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    ) -> None:
        expected = settings.api_token.get_secret_value()
        supplied = credentials.credentials if credentials else ""
        if not secrets.compare_digest(supplied, expected):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid bearer token",
                headers={"WWW-Authenticate": "Bearer"},
            )

    Auth = Annotated[None, Depends(authenticate)]

    @app.get("/healthz", response_model=HealthView)
    async def health() -> HealthView:
        return HealthView(
            status="ok" if worker.ready else "starting",
            scheduler_running=bool(worker.scheduler_task and not worker.scheduler_task.done()),
        )

    @app.post("/tasks", response_model=TaskView, status_code=status.HTTP_202_ACCEPTED)
    async def submit_task(task: TaskRequest, _: Auth) -> TaskView:
        if task.timeout_seconds > settings.max_task_timeout_seconds:
            raise HTTPException(
                status_code=422,
                detail=f"timeout_seconds cannot exceed {settings.max_task_timeout_seconds}",
            )
        task_id = await repository.create(task)
        record = await repository.get(task_id)
        assert record is not None
        return _to_view(record)

    @app.get("/tasks/{task_id}", response_model=TaskView)
    async def get_task(task_id: str, _: Auth) -> TaskView:
        try:
            record = await repository.get(task_id)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail="Task not found") from exc
        if record is None:
            raise HTTPException(status_code=404, detail="Task not found")
        return _to_view(record)

    return app


def _to_view(record) -> TaskView:
    return TaskView(
        id=record.id,
        status=record.status,
        cpu_cores=record.payload.cpu_cores,
        memory_mb=record.payload.memory_mb,
        created_at=record.created_at,
        updated_at=record.updated_at,
        started_at=record.started_at,
        finished_at=record.finished_at,
        exit_code=record.exit_code,
        error=record.error,
        db_logs=record.db_logs,
        loadgen_logs=record.loadgen_logs,
    )
