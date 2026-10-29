# ADR-0003: Drag-and-drop tile IDs only; no free-text input to the model

**Status:** Accepted (2026-10-29)

## Context
A public prompt-injection game that accepts free text invites abusive content, pasted PII,
real jailbreak payloads and unbounded token costs.

## Decision
Players compose attempts from fixed tiles. The browser sends tile IDs only; the server
validates them against `content/tiles.yaml` and assembles the prompt. Attempts are
single-turn.

## Consequences
- The attack space is enumerable (6 x 6 x 6 x 4 = 864 combos per level), so evals can cover
  every possible attack and results can be cached and replayed.
- Costs are bounded and predictable.
- Less open-ended than free text; daily rotation, nerve budgets and levels keep it replayable.
