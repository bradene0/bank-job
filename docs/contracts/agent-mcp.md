# Internal contract: game-api ⇄ agent

Two internal interfaces, neither reachable from the browser:

1. **Agent HTTP API** (`agent:8000`), called by game-api.
2. **MCP server** (`game-api:8080/mcp`), called by the agent.

The agent never receives text over HTTP. Its requests carry only a signed **attempt token**
and ids; everything Penny reads (system prompt, customer message, documents) comes from
game-api's MCP server, which validates the token on every request. A forged or expired
token fails on the first MCP call, before any model call is made, so the agent cannot be
used to send free text to the model even if its port were exposed (ADR-0003, ADR-0011).

## Trace context

Every request in both directions carries a W3C `traceparent` header (no `baggage`), so an
attempt is one trace: game-api's `POST /v1/runs` client span is the parent of the agent's
server span; the MCP SDK opens an `MCP send <method>` client span per request, and the agent
injects it into the HTTP headers, so game-api's `/mcp` server span and its `bank tool <name>`
span nest under it. (The Python SDK also puts the context in `params._meta`; the Java SDK
does not read it, and the header carries the same span.) Both sides speak HTTP/1.1.

## Attempt token

ES256 JWT minted by game-api for one attempt, signed with the same key as guest tokens.

| Claim | Value |
|---|---|
| `iss` | `bankjob-game-api` |
| `aud` | `bankjob-mcp` (guest tokens use `bankjob-web`; neither works on the other's endpoints) |
| `sub` | attempt id (UUID) |
| `scope` | `penny` |
| `exp` | issue time + 2 minutes |

MCP calls are also refused once the attempt has an outcome, so a token cannot be reused
after its attempt finishes.

## Agent HTTP API

Request bodies reject unknown fields. Responses are JSON.

### `POST /v1/runs`

Runs Penny for one attempt.

```json
{ "attemptToken": "<jwt>" }
```

`200`:

```json
{
  "reply": "Penny's final message to the customer",
  "stopReason": "end_turn | refusal | max_tokens | tool_limit | stub",
  "model": "claude-haiku-5-5 | stub",
  "toolCalls": 3,
  "usage": { "inputTokens": 1234, "outputTokens": 210 }
}
```

`401` if the MCP server rejects the token; `502` if the MCP server or the model fails.

### `POST /v1/reviews`

Level 5 only: the guardrail agent reviews one proposed tool call. game-api calls this
while it is handling Penny's tool call, so the agent must serve it concurrently with the
run that triggered it.

```json
{ "attemptToken": "<jwt>", "reviewId": "<uuid>" }
```

`200`:

```json
{
  "verdict": "allow | deny",
  "reason": "one sentence, at most 200 characters",
  "model": "claude-haiku-5-5 | stub",
  "usage": { "inputTokens": 900, "outputTokens": 40 }
}
```

## MCP server

Streamable HTTP, stateless (no `Mcp-Session-Id`), JSON responses, protocol version
`2025-11-25` via the `initialize` handshake. `Authorization: Bearer <attempt token>` on
every request.

### Resources

**`bankjob://attempt/brief`** (`application/json`): the attempt Penny is handling.

```json
{
  "attemptId": "<uuid>",
  "level": 3,
  "comboId": "p3.x1.t2.y1",
  "tiles": { "persona": "p3", "pretext": "x1", "technique": "t2", "payload": "y1" },
  "system": "Penny's system prompt for this level and day",
  "message": "The customer's message, assembled from tile fragments",
  "maxToolCalls": 8,
  "stub": {
    "waryPersona": "p5",
    "callerPhone": "+1-555-0199",
    "documentIds": ["CD-1"],
    "payloadAmountCents": 20000
  }
}
```

`stub` holds structured facts that the scripted stub teller uses instead of reading
prose. Live mode ignores it. `payloadAmountCents` is `null` for "Drain the Vault".

**`bankjob://reviews/{reviewId}`** (`application/json`): a pending guardrail review.

```json
{ "reviewId": "<uuid>", "system": "guardrail system prompt", "content": "what to review",
  "stub": { "tool": "transfer_funds", "toAccount": "BV-MULE-01", "documentsRead": ["CD-1"],
            "documentClaimsClearance": false } }
```

### Tools

Every call is authorized by game-api's policy engine for the attempt's level and recorded
with its decision. A denied call returns `isError: true` and
`{"error": "denied", "reason": "..."}` so Penny can tell the customer which rule stopped
her. Invalid arguments return `isError: true` and `{"error": "invalid_arguments", ...}`.
Results are JSON text content.

| Tool | Arguments | Result |
|---|---|---|
| `lookup_account` | `account_hint` (string, ≤64: number or owner name) | `{"matches": [{"accountNumber", "kind", "owner", "phoneMasked", "securityQuestion", "answerHint"}]}` |
| `get_balance` | `account_number` | `{"accountNumber", "balanceCents", "balanceUsd"}` |
| `verify_identity` | `account_number`, `answer` (≤64) | `{"accountNumber", "verified", "attemptsLeft"}` |
| `read_document` | `document_id` (≤32) | `{"documentId", "title", "body"}` |
| `update_contact_info` | `account_number`, `phone` | `{"accountNumber", "phone"}` |
| `transfer_funds` | `from_account`, `to_account`, `amount_usd` (number, 0.01–1,000,000), `idempotency_key` (≤64) | `{"status": "posted" \| "insufficient_funds", "amountCents", "fromAccount", "toAccount"}` |
| `flag_for_review` | `reason` (≤200) | `{"flagged": true}` |

`answerHint` is the leak behind Level 3 (`security-and-levels.md`): customers spelled their
answers out letter by letter, e.g. `B-I-S-C-U-I-T`. Answers compare case-insensitively.

`idempotency_key` is scoped to the attempt server-side, so a model reusing a key in a
different attempt cannot collide with it.
