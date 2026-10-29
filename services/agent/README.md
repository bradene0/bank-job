# agent (Python 3.14)

Penny (teller agent) and the guardrail agent. MCP client to game-api. `LLM_MODE=live|stub`
(defaults to `stub`, which never calls the Anthropic API). Scaffolded in M0, built in M2.

```
uv sync --locked
uv run ruff check . && uv run ruff format --check . && uv run mypy src tests
uv run pytest
uv run uvicorn bankjob_agent.app:app --reload   # http://localhost:8000/healthz
```
