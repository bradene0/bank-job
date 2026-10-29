# ADR-0004: GraalVM native image for game-api

**Status:** Accepted, with a fallback (2026-10-29)

## Context
The instance cold-starts often (scale-to-zero) and has limited RAM shared with Kafka,
Postgres, the Python agent and tracing. A JVM Spring Boot app uses several hundred MB and
starts in seconds; a native image uses far less memory and starts in well under a second.

## Decision
Build game-api as a GraalVM native image. Use Micrometer Tracing with the OpenTelemetry
bridge/exporter for tracing, because the OpenTelemetry Java agent relies on bytecode
instrumentation and does not work with native images.

## Fallback
If native compilation blocks progress (for example, reflection hints for a library),
ship the JVM build with a tuned heap, record measured memory and startup numbers, and
update this ADR. Do not spend more than a day fighting native-image issues.
