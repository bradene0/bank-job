# ADR-0011: The agent takes only a signed token; everything else comes over MCP

**Status:** Accepted (2026-10-29)

## Context
Penny runs in the Python agent service; the bank tools, the ledger and the policy engine
live in game-api. The agent needs the attempt's prompt, the customer's message and the
tools. The obvious design, game-api POSTing `{system, message}` to the agent, gives the
agent an endpoint that takes free text and sends it to the model. That endpoint would be
one exposed port or one SSRF away from breaking ADR-0003 (no free text to the model).

## Decision
- game-api mints a per-attempt **attempt token** (ES256, audience `bankjob-mcp`, scope
  `penny`, subject = attempt id, 2-minute lifetime) and sends the agent only that token.
  Guardrail reviews add a review id. The agent's request bodies have no text fields.
- The agent connects to game-api's **MCP server** (`/mcp`) with the token and reads its
  brief (`bankjob://attempt/brief`) and any pending review (`bankjob://reviews/{id}`) as
  MCP resources, then calls the bank tools over MCP. A bad token fails on the first MCP
  request, before any model call.
- The MCP server is the official Java SDK (`io.modelcontextprotocol.sdk:mcp-core` 2.1) on
  its **stateless** servlet transport: every request carries the token, so no MCP session
  state is needed, and the request thread keeps Spring Security's context.
- Spring Security runs a separate filter chain for `/mcp` that accepts attempt tokens
  only. Guest tokens and attempt tokens are each rejected on the other's endpoints.
- The token names the attempt, and the attempt row names the session and level. The
  model cannot choose which branch or which level's controls apply.
- The Python agent uses the official `mcp` client with the `initialize` handshake
  (protocol 2025-11-25), the newest revision the Java SDK speaks.

## Consequences
- The agent stays thin and has no text input at all. That is easy to audit and to
  explain.
- Every Level 5 guardrail review costs one extra MCP round trip, a few milliseconds on the
  internal network.
- Reviews are re-entrant: game-api calls the agent's `/v1/reviews` while it is handling a
  tool call from that same agent's run. Both sides must serve requests concurrently:
  virtual threads in game-api, asyncio in the agent.
- MCP tool schemas are strict (`additionalProperties: false`, all properties required)
  because the agent passes them to the model with strict tool use. Length limits live in
  descriptions and in game-api's own validation, since strict schemas do not support them.
