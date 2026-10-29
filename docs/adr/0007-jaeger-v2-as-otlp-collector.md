# ADR-0007: Jaeger v2 doubles as the OTLP collector

**Status:** Accepted (2026-10-29)

## Context
The architecture called for a separate OpenTelemetry Collector in front of Jaeger. Jaeger v2
is itself a distribution of the OpenTelemetry Collector: it runs the same receiver →
processor → exporter pipeline, plus trace storage and the query UI. The upstream
`otel/opentelemetry-collector-contrib` image is distroless (no shell), so Docker Compose
cannot health-check it without building a custom image. Every container also costs RAM on
the single `t4g.large`.

## Decision
Run one Jaeger v2 container configured as the collector (`infra/compose/jaeger.yaml`):
OTLP gRPC/HTTP receivers, `memory_limiter` and `batch` processors, bounded in-memory
storage. Services export OTLP to `jaeger:4317`.

## Consequences
- One fewer container to run, size and health-check; the pipeline is still a standard
  OTel Collector config, so the same processors (sampling, attribute filtering) apply.
- If we later need to fan traces out to a second backend, or do tail sampling separately
  from storage, reintroduce a standalone collector. Services only see an OTLP endpoint,
  so that is a config change, not a code change.
