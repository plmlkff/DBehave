FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :plugins:clickhouse-load:installDist

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/plugins/clickhouse-load/build/install/clickhouse-load/ ./
EXPOSE 8080
ENTRYPOINT ["/app/bin/clickhouse-load"]
