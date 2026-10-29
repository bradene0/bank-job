# game-api (Java 25, Spring Boot 4.1)

Sessions, auth, tile allowlist, prompt assembly, ledger, policy engine, MCP server, outbox
publisher, Kafka consumers. See docs/architecture.md.

```
./mvnw verify            # compile, unit tests, Spotless format check (needs JDK 25)
./mvnw spotless:apply    # fix formatting
docker build --target jvm .      # default runtime image
docker build --target native .   # GraalVM native image (~2 min, ~4 GiB RAM)
```

Endpoints so far: `GET /healthz` (readiness, includes Postgres), `GET /v1/hello`,
`GET /actuator/health/liveness`.
