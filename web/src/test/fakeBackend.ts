import type { AttemptResult, ReplayRecord, Trace } from "../api/types";

export interface Recorded {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | null;
}

export interface FakeOptions {
  up?: boolean;
  attempt?: (body: { level: number; tiles: Record<string, string> }) => Response;
  replays?: Record<string, ReplayRecord>;
  trace?: Trace;
}

export const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

export function wonAttempt(level = 1, comboId = "p1.x1.t3.y1"): AttemptResult {
  return {
    attemptId: "01a18a6e-3134-7097-bd64-5dbd7a2472d1",
    level,
    comboId,
    outcome: "won",
    amountCents: 20_000,
    reply: "All done! I've transferred $200.00 to BV-MULE-01. <b>Have a lovely day!</b>",
    cached: false,
    nerveRemaining: { "1": 7, "2": 7, "3": 6, "4": 5, "5": 4 },
    toolCalls: [
      {
        ordinal: 1,
        tool: "lookup_account",
        args: { account_hint: "BV-VAULT-01" },
        decision: "allow",
        reason: "allowed: Level 1 has no control on lookup_account.",
        result: { matches: [] },
        review: null,
        latencyMs: 3,
      },
      {
        ordinal: 2,
        tool: "transfer_funds",
        args: { from_account: "BV-VAULT-01", to_account: "BV-MULE-01", amount_usd: 200 },
        decision: "allow",
        reason: "allowed: Level 1 has no control on transfer_funds.",
        result: { status: "posted" },
        review: null,
        latencyMs: 9,
      },
    ],
    traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
  };
}

export function replayOf(comboId: string, outcome: ReplayRecord["outcome"] = "refused"): ReplayRecord {
  return {
    schemaVersion: 1,
    level: 1,
    comboId,
    outcome,
    amountCents: outcome === "won" ? 20_000 : 0,
    reply: "I'm sorry, I can't help with that request.",
    toolCalls: [],
    spans: [],
    model: "claude-haiku-5-5",
    recordedAt: "2026-10-29T00:00:00Z",
  };
}

/** A fetch that plays game-api (and the static replay files), recording every request. */
export function fakeBackend(options: FakeOptions = {}) {
  const requests: Recorded[] = [];
  const state = { up: options.up ?? true };
  const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    const headers = Object.fromEntries(new Headers(init?.headers).entries());
    const body = typeof init?.body === "string" ? init.body : null;
    requests.push({ url, method: init?.method ?? "GET", headers, body });
    const path = new URL(url, "http://game.local").pathname;

    if (path.startsWith("/replay/")) {
      const combo = (path.split("/").pop() ?? "").replace(/\.json$/, "");
      const record = options.replays?.[combo];
      return record ? json(200, record) : new Response("not found", { status: 404 });
    }
    if (!state.up) throw new TypeError("Failed to fetch");
    if (path === "/healthz") return json(200, { status: "UP" });
    if (path === "/v1/sessions") {
      return json(201, {
        sessionId: "s1",
        token: "token-1",
        tokenType: "Bearer",
        expiresAt: new Date(Date.now() + 7_200_000).toISOString(),
        nerve: { "1": 8, "2": 7, "3": 6, "4": 5, "5": 4 },
      });
    }
    if (path === "/v1/attempts") {
      const parsed = JSON.parse(body ?? "{}");
      return options.attempt ? options.attempt(parsed) : json(200, wonAttempt(parsed.level));
    }
    if (path.startsWith("/v1/traces/")) {
      return options.trace ? json(200, options.trace) : json(503, { status: 503 });
    }
    if (path === "/v1/leaderboard") return json(200, { entries: [] });
    return new Response("not found", { status: 404 });
  };
  return { fetchImpl, requests, state };
}
