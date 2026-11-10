# Сборка из корня репозитория:
#   docker build -f plugins/postgres-load/Dockerfile -t dbehave/postgres-load:0.1.0 .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
# Кэш ~/.gradle переживает пересборки: Gradle и зависимости скачиваются только в первый раз
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :plugins:postgres-load:installDist

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/plugins/postgres-load/build/install/postgres-load/ ./
EXPOSE 8080
# exec в скрипте запуска делает java процессом PID 1, поэтому SIGTERM от воркера доходит до приложения
ENTRYPOINT ["/app/bin/postgres-load"]
