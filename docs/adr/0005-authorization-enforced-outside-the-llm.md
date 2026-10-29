# ADR-0005: Authorization is enforced by the policy engine, never by the LLM

**Status:** Accepted (2026-10-29)

## Context
An LLM agent with tool access can be talked into anything a prompt allows. The game's
lesson is that agent security comes from authorization, not prompt wording.

## Decision
Every MCP tool call from Penny is checked by the game-api policy engine using the current
level's controls (`content/levels.yaml`). Penny's service token carries level-specific
scopes. The model's output is never treated as an authorization decision. Every decision
is recorded and traced.

## Consequences
- Levels 2–5 are real security controls that hold regardless of what Penny says.
- Intended weaknesses (Levels 3–4) are deliberate gaps in policy design, documented in
  `docs/security-and-levels.md`, not model failures.
