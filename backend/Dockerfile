FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon :backend:bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/dbehave.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
