FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :plugins:mongo-load:installDist

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/plugins/mongo-load/build/install/mongo-load/ ./
EXPOSE 8080
ENTRYPOINT ["/app/bin/mongo-load"]
