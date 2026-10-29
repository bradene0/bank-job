# game-api (Java 25, Spring Boot 4.1)

Sessions, auth, tile allowlist, prompt assembly, ledger, policy engine, MCP server, outbox
publisher, Kafka consumers. See docs/architecture.md and docs/data-model.md.

```
./mvnw verify            # unit + jqwik property tests, then Testcontainers ITs (needs Docker), Spotless
./mvnw spotless:apply    # fix formatting
docker build -f services/game-api/Dockerfile --target jvm .      # from the repo root
docker build -f services/game-api/Dockerfile --target native .   # GraalVM native image
```

Running outside Docker (against the compose Postgres) needs a signing key and salt, or
`BANKJOB_ALLOW_EPHEMERAL_SECRETS=true`:

```
scripts/generate-dev-jwt-key.sh                 # writes dev-keys/jwt-es256.pem (gitignored)
JWT_SIGNING_KEY_PATH=$PWD/dev-keys/jwt-es256.pem IP_HASH_SALT=dev \
  POSTGRES_PASSWORD=change-me-local-only ./mvnw spring-boot:run
```

Modules (packages): `ledger` (double-entry core; depends on nothing else), `branch`
(per-session seed data), `session` + `security` (guest sessions, ES256 JWTs), `accounts`
(read API), `content` (levels and tiles from `content/`), `maintenance` (partitions).
Boundaries are checked by `ArchitectureTest`.

Endpoints: `GET /healthz` (readiness, includes Postgres), `POST /v1/sessions`,
`GET /v1/accounts`, `GET /v1/accounts/{number}`, `GET /v1/hello`. Contract:
`docs/contracts/openapi.yaml`.
