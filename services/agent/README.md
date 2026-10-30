# agent (Python 3.14)

Penny (the teller agent) and the Level 5 guardrail agent. An MCP client of game-api; the
internal contract is in [`docs/contracts/agent-mcp.md`](../../docs/contracts/agent-mcp.md).

The agent takes no text over HTTP. A request carries only game-api's signed attempt token
(and a review id); the system prompt, customer message and documents are read from
game-api's MCP server with that token. Every tool Penny calls is authorized by game-api's
policy engine, so nothing in this service is a security control.

| Endpoint | Purpose |
|---|---|
| `POST /v1/runs` `{attemptToken}` | Run Penny for one attempt |
| `POST /v1/reviews` `{attemptToken, reviewId}` | Guardrail verdict on one proposed tool call (Level 5) |
| `GET /healthz` | Liveness, LLM mode and model |

## Modes

- `LLM_MODE=stub` (default): a scripted, deterministic Penny and guardrail. Never calls the
  Anthropic API; used by tests, load tests and local development.
- `LLM_MODE=live`: Claude (`ANTHROPIC_MODEL`, default `claude-haiku-5-5`) with a manual tool
  loop over MCP, thinking disabled and effort `low` to keep attempts fast and cheap. The
  guardrail uses structured output and fails closed.

| Variable | Default | |
|---|---|---|
| `LLM_MODE` | `stub` | `stub` or `live` |
| `ANTHROPIC_API_KEY` | | Required in live mode |
| `ANTHROPIC_MODEL` | `claude-haiku-5-5` | |
| `GAME_API_MCP_URL` | `http://game-api:8080/mcp` | |
| `PENNY_MAX_TOOL_CALLS` | `8` | Agent-side cap; game-api enforces its own |
| `LLM_TIMEOUT_SECONDS` | `20` | Per model request |

## Development

```
uv sync --locked
uv run ruff check . && uv run ruff format --check . && uv run mypy src tests
uv run pytest
uv run uvicorn bankjob_agent.app:app --reload   # http://localhost:8000/healthz
```

Live-mode tests run the real Anthropic SDK against a local fake of the Messages API, so the
suite never spends tokens. The MCP client is tested against a real MCP server over HTTP.

### Live-mode smoke test without a key

`scripts/fake_messages_api.py` is a stdlib-only fake of the Messages API that scripts a
gullible Penny (look up the vault, verify with the leaked hint, wire $200). It exercises
the whole live path, from game-api through the agent and the real SDK to MCP, the policy
engine and the ledger, without a key or any cost:

```
docker run -d --name fake-anthropic --network bankjob_default \
  -v "$PWD/services/agent/scripts:/app:ro" -v /tmp/fake-log:/log \
  python:3.14.10-slim-trixie python /app/fake_messages_api.py
printf 'services:\n  agent:\n    environment:\n      ANTHROPIC_BASE_URL: http://fake-anthropic:8999\n' > /tmp/fake.yml
LLM_MODE=live ANTHROPIC_API_KEY=fake docker compose -f docker-compose.yml -f /tmp/fake.yml \
  up -d --wait game-api agent
```

Then play a Level 3 attempt. Request bodies are logged to `/tmp/fake-log/requests.jsonl`.
