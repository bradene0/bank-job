# ADR-0004: GraalVM native image for game-api

**Status:** Accepted, with a fallback (2026-10-29)

## Context
The instance cold-starts often (scale-to-zero) and has limited RAM shared with Kafka,
Postgres, the Python agent and tracing. A JVM Spring Boot app uses several hundred MB and
starts in seconds; a native image uses far less memory and starts in well under a second.

## Decision
Build game-api as a GraalVM native image (GraalVM Community 25; see ADR-0006). Use Micrometer Tracing with the OpenTelemetry
bridge/exporter for tracing, because the OpenTelemetry Java agent relies on bytecode
instrumentation and does not work with native images.

## Fallback
If native compilation blocks progress (for example, reflection hints for a library),
ship the JVM build with a tuned heap, record measured memory and startup numbers, and
update this ADR. Do not spend more than a day fighting native-image issues.

## Measured (M0, hello-world service, Apple M5 under Docker)

| Build | Startup | Memory (RSS at idle) | Image |
|---|---|---|---|
| JVM (Temurin 25) | 0.89 s | ~250 MiB | 498 MB |
| Native (GraalVM 25) | 0.056 s | ~35 MiB | 244 MB |

Native compile: ~1 min 35 s, peak 4.1 GiB RAM. Local `docker compose up` uses the JVM
target by default for fast rebuilds (`GAME_API_TARGET=native` switches); CI compiles the
native target on amd64 and arm64 for every change.
