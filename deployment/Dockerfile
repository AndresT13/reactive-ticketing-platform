# syntax=docker/dockerfile:1.7
# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
# Copy the POMs first so the dependency layer is cached between source changes.
COPY pom.xml .
COPY domain/model/pom.xml domain/model/
COPY domain/usecase/pom.xml domain/usecase/
COPY infrastructure/helpers/command-messages/pom.xml infrastructure/helpers/command-messages/
COPY infrastructure/driven-adapters/dynamodb-repository/pom.xml infrastructure/driven-adapters/dynamodb-repository/
COPY infrastructure/driven-adapters/sqs-publisher/pom.xml infrastructure/driven-adapters/sqs-publisher/
COPY infrastructure/driven-adapters/payment-simulator/pom.xml infrastructure/driven-adapters/payment-simulator/
COPY infrastructure/entry-points/reactive-web/pom.xml infrastructure/entry-points/reactive-web/
COPY infrastructure/entry-points/sqs-listener/pom.xml infrastructure/entry-points/sqs-listener/
COPY infrastructure/entry-points/scheduler/pom.xml infrastructure/entry-points/scheduler/
COPY applications/app-service/pom.xml applications/app-service/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -DexcludeReactor=true || true
COPY . .
# Tests run in CI (mvn verify); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar applications/app-service/target/app.jar extract --layers --launcher --destination /workspace/extracted

# ---------- Runtime stage ----------
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
# Spring Boot layered jar: dependencies change less often than application code.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health/liveness >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
