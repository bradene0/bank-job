# ADR-0012: The response cache stores model decisions, never outcomes

**Status:** Accepted (2026-10-29)

## Context
Attempts are single-turn and enumerable (864 combos per level), and every session's branch
starts identical (ADR-0009), so the same combo on the same day gives the model the same
input. Caching avoids paying for the same model call twice. But each session has its own
ledger. A cache that stored "won, $200" would report a win without moving money in the
new session, so the scoreboard and the ledger would disagree. It would also skip the
policy engine for every cached attempt.

## Decision
- The cache key is `(level, dailySeed, comboId, variant)`. `variant` is the model id (or
  `stub`) plus a fingerprint of every content file, so editing a prompt, a tile or a level
  can never serve a stale decision.
- The cached value is a **transcript** of what the model decided: its tool calls in order,
  with arguments, any guardrail verdicts, and its reply.
- On a hit, game-api replays the tool calls through the same `BankTools` path a live run
  uses: argument validation, the policy engine and this session's ledger. Each replayed
  call gets a fresh, recorded policy decision. The outcome comes from this session's
  ledger, as it does for a live run.
- Recorded guardrail verdicts are reused, so a cache hit never runs Penny. A replayed call the
  guardrail never saw (another check stopped it in the recorded session) is reviewed when the
  replay reaches it, or denied if the model budget is spent (amended 2026-10-30, below).

## Consequences
- Cache hits are real attempts: money moves, every control applies, and the timeline is
  recorded. Hits also skip the agent entirely, so they are fast and free.
- A session's state can differ from the cached run's: an earlier win, a changed phone, a
  drained vault. Penny's cached reply text may then not match the replayed outcome. The
  outcome is authoritative and the reply is flavor.
- Content changes invalidate the cache automatically. Old rows stay until retention runs.

## Amendment (2026-10-30): review calls the recorded run never got to
At first a replayed sensitive call with no recorded verdict was denied (fail closed). The eval
matrix showed what that does to Level 5 once sessions carry state across levels (ADR-0016): a
first player's attempt is stopped by step-up, so the guardrail is never asked; a later player
who changed the vault's phone on Level 4 gets the cached decision, passes step-up, and was then
denied for want of a verdict. Whether the Level 4 to Level 5 chain worked depended on who played
the combo first that day. Now the guardrail reviews such a call during the replay, exactly as it
would in a live attempt. Only Level 5 sensitive calls that pass every deterministic check get
there, so cache hits that need a model call are rare; over the model budget they still fail
closed.
