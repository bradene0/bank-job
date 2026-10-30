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

Modules (packages):

| Package | Role |
|---|---|
| `ledger` | Double-entry core; depends on nothing else |
| `branch` | Per-session seed data and the account directory the tools read |
| `session`, `security` | Guest sessions, nerve, ES256 guest and attempt tokens |
| `content` | Levels, tiles, daily rotation and prompts from `content/` |
| `attempt` | Attempt and tool-call storage, server-side prompt assembly |
| `policy` | The policy engine: per-level authorization of every tool call |
| `tools` | Bank tool execution: parse, authorize, act, record |
| `mcp` | MCP server for the agent (stateless streamable HTTP at `/mcp`) |
| `play` | `POST /v1/attempts`: nerve, cache, agent call, outcome, budget |
| `accounts`, `maintenance` | Read API; attempt partitions |

Boundaries are checked by `ArchitectureTest`. For example, the policy engine cannot see
transport or the agent, and only the tools and branch provisioning can move money.

Endpoints: `GET /healthz` (readiness, includes Postgres), `POST /v1/sessions`,
`POST /v1/attempts`, `GET /v1/accounts`, `GET /v1/accounts/{number}`, `GET /v1/hello`.
Contract: `docs/contracts/openapi.yaml`. Internal: `POST /mcp` (attempt tokens only;
`docs/contracts/agent-mcp.md`).
