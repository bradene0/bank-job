// Response shapes from docs/contracts/openapi.yaml.

export type Outcome = "won" | "blocked" | "refused";

export interface ToolCall {
  ordinal: number;
  tool: string;
  args: Record<string, unknown>;
  decision: "allow" | "deny";
  /** "code: explanation", e.g. "teller_limit: Teller transfers are limited to ..." */
  reason: string;
  result: Record<string, unknown>;
  review: Record<string, unknown> | null;
  latencyMs: number;
}

export interface AttemptResult {
  attemptId: string;
  level: number;
  comboId: string;
  outcome: Outcome;
  amountCents: number;
  reply: string;
  cached: boolean;
  nerveRemaining: Record<string, number>;
  toolCalls: ToolCall[];
  traceId: string | null;
}

export interface Span {
  spanId: string;
  parentSpanId: string | null;
  service: string;
  name: string;
  kind: "unspecified" | "internal" | "server" | "client" | "producer" | "consumer";
  startOffsetMs: number;
  durationMs: number;
  error: boolean;
  attributes: Record<string, string | number | boolean>;
}

export interface Trace {
  traceId: string;
  complete: boolean;
  spans: Span[];
}

export interface Session {
  sessionId: string;
  token: string;
  tokenType: "Bearer";
  expiresAt: string;
  nerve: Record<string, number>;
}

export interface LeaderboardEntry {
  rank: number;
  alias: string;
  score: number;
  bestLevel: number;
  stolenCents: number;
}

/** docs/contracts/replay.schema.json: a recorded attempt served as a static file. */
export interface ReplayRecord {
  schemaVersion: 1;
  level: number;
  comboId: string;
  outcome: Outcome;
  amountCents: number;
  reply: string;
  toolCalls: ToolCall[];
  spans: Span[];
  model: string;
  recordedAt: string;
}
