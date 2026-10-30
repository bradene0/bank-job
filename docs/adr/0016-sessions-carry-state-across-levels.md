# ADR-0016: A session carries its state across levels; the vault is restocked per level

**Status:** Accepted (2026-10-30)

## Context
A guest session has one branch (ADR-0009), and the game plays every level in that session. So
whatever a player does on one level is still true on the next:

- A phone changed through the Level 4 gap (`update_contact_info` has no step-up there) is still
  the caller's phone on Level 5. Level 5's step-up text then reaches the caller, and only the
  velocity rule and the guardrail stand between the player and the vault.
- Money stays where it went. "Drain the Vault" on Level 1 moves all $250,000 to the mule, and
  every later wire in that session ends in `insufficient_funds`: Levels 2-5 become a dead end.
- Level 5's velocity rule reads every teller credit to the mule in the last 15 minutes, including
  wins on Level 4.

Options considered:
1. Reset the branch per level (phones, balances, signals).
2. Make contact changes last for one attempt only, like `verify_identity`.
3. Keep the session's state, and restock only the vault.

## Decision
Option 3. Levels are one heist in progress, not separate puzzles: the game keeps memory. Changed
phones, the mule's haul and the anomaly history all carry over. The one exception is the vault:
when a session starts a level it has not played yet, head office tops the vault back up to its
opening balance (`BranchProvisioner.restockVault`). Within a level, money taken stays taken.

The restock is a ledger transfer from the session's equity account with no attempt, so it follows
the double-entry rules, appears in the audit log, and is not a teller credit for the anomaly rules.
"First attempt on a level" comes from the session's nerve: the spend that leaves the level one
below its starting nerve. A refunded attempt (503) makes the next one "first" again, which only
fills a shortfall, so it is harmless.

The Level 4 to Level 5 phone chain is therefore an intended weakness of Level 5: protecting
contact changes on one level does not undo a change made before it existed. It is bounded by the
velocity rule (a Level 4 win within 15 minutes blocks Level 5 transfers to the mule) and the
guardrail.

## Consequences
- No dead end after a big early win; scores stay comparable across levels.
- Level 5 has a reachable path for players who explore (change the phone on Level 4 without
  winning, then come back), which the eval matrix measures as its own setup
  (`services/evals`, "after an L4 phone change").
- Replay files are recorded from fresh branches, so in replay mode the chain does not exist.
- A cached decision (ADR-0012) replays into whatever state the session is in, so a chained
  Level 5 attempt can reuse a decision Penny made for a fresh branch. The outcome still comes
  from this session's ledger and policy.
