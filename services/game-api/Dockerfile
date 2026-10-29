# syntax=docker/dockerfile:1
#
# Two runtime targets (ADR-0004):
#   jvm    - default; fast local builds for `docker compose up`
#   native - GraalVM native image; used in production and compiled in CI
#
#   docker build --target jvm    -t game-api:jvm    .
#   docker build --target native -t game-api:native .

ARG TEMURIN_JDK=eclipse-temurin:25.0.5_8-jdk-noble
ARG TEMURIN_JRE=eclipse-temurin:25.0.5_8-jre-noble
ARG GRAALVM=ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1
ARG RUNTIME_BASE=ubuntu:24.04

# ---------- JVM build ----------
FROM ${TEMURIN_JDK} AS build-jvm
# The Maven wrapper needs unzip to use the checksum-pinned .zip distribution.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/game-api-*.jar extract --layers --launcher --destination /extracted

# ---------- Native build ----------
FROM ${GRAALVM} AS build-native
RUN microdnf install -y unzip && microdnf clean all
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -Pnative dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -Pnative native:compile -DskipTests

# ---------- JVM runtime ----------
FROM ${TEMURIN_JRE} AS jvm
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
# Layered copy: dependencies change rarely, so app-only rebuilds reuse cached layers.
COPY --from=build-jvm /extracted/dependencies/ ./
COPY --from=build-jvm /extracted/spring-boot-loader/ ./
COPY --from=build-jvm /extracted/snapshot-dependencies/ ./
COPY --from=build-jvm /extracted/application/ ./
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# ---------- Native runtime ----------
FROM ${RUNTIME_BASE} AS native
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build-native /src/target/game-api /app/game-api
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/game-api"]
