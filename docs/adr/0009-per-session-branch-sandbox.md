# ADR-0009: Every session gets its own sandboxed branch

**Status:** Accepted (2026-10-29)

## Context
The original data model had one shared set of customers and accounts: a single
$250,000 vault and a single `BV-MULE-01` that every player wires money into. With a public
game, that breaks quickly:

- One player who drains the vault empties it for everyone, and later players see a
  different game.
- All winnings land in one mule account, so "how much did I steal" cannot come from the
  ledger.
- Level 4's `update_contact_info` would change a customer's phone for every player.
- Cached and replayed results assume the same starting state, but shared state drifts.
- Every session would update the same few balance rows, a hot spot for optimistic locking.

## Decision
`POST /v1/sessions` provisions a private copy of the branch in the same transaction as
the session row: the five fictional customers, their checking accounts, the vault, the
player's `BV-MULE-01`, and a per-session `BV-EQUITY-01` opening-balance account. One
journal entry funds everything from that equity account, so the opening balances follow
the same double-entry rules as every other movement. `customers.session_id` and
`accounts.session_id` are `NOT NULL`; account numbers are unique per session.

Transfers check that both accounts belong to the caller's session, and the accounts API
takes the session from the token's subject, never from the request.

## Consequences
- Players cannot interfere with each other, and each attempt starts from a known state,
  which keeps the eval matrix and replay files honest.
- The ledger's net balance per session is exactly zero (equity is the only negative
  account), which gives tests a simple invariant to check.
- Each session writes about 31 rows (1 session, 5 customers, 8 accounts, 8 balances, 1 entry,
  8 postings). Per-IP session rate limits bound the growth, and retention will drop
  expired sessions (to be implemented with partition retention).
- Security answers are bcrypt-hashed once at startup and shared by all sessions'
  copies, so creating a session stays fast.
