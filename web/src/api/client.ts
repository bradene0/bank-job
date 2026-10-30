import type { SlotKey } from "../content/types";
import { newTraceContext } from "./traceparent";
import type { AttemptResult, LeaderboardEntry, ReplayRecord, Session, Trace } from "./types";

/** Live play is not possible right now; the game falls back to replay mode. */
export class LiveUnavailableError extends Error {
  constructor(
    readonly reason: "agent_unavailable" | "over_budget" | "unreachable" | "rate_limited" | "server_error",
  ) {
    super(`Live play unavailable: ${reason}`);
  }
}

/** No attempts left for this level in this session (nerve does not come back with time). */
export class NerveExhaustedError extends Error {}

/** The guest token expired or was rejected; start a new session. */
export class SessionExpiredError extends Error {}

export interface PlayedLive {
  result: AttemptResult;
  /** Round trip as the browser measured it, for the "Under the hood" waterfall. */
  clientMs: number;
}

const TIMEOUT_MS = 30_000;

/**
 * The browser's whole API surface. It only ever sends tile ids and a level (ADR-0003); there is
 * no request that carries text.
 */
export class ApiClient {
  constructor(
    private readonly baseUrl: string,
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
  ) {}

  async healthy(timeoutMs = 2_500): Promise<boolean> {
    try {
      const response = await this.request("/healthz", { method: "GET" }, timeoutMs);
      return response.ok;
    } catch {
      return false;
    }
  }

  async createSession(): Promise<Session> {
    const response = await this.request("/v1/sessions", { method: "POST" });
    if (response.status === 429) throw new LiveUnavailableError("rate_limited");
    if (!response.ok) throw new LiveUnavailableError("server_error");
    return (await response.json()) as Session;
  }

  async attempt(token: string, level: number, tiles: Record<SlotKey, string>): Promise<PlayedLive> {
    const trace = newTraceContext();
    const started = performance.now();
    const response = await this.request("/v1/attempts", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        traceparent: trace.header,
      },
      body: JSON.stringify({ level, tiles }),
    });
    const clientMs = performance.now() - started;
    if (response.ok) {
      return { result: (await response.json()) as AttemptResult, clientMs };
    }
    switch (response.status) {
      case 401:
        throw new SessionExpiredError();
      case 429:
        throw new NerveExhaustedError();
      case 503: {
        const problem = (await response.json().catch(() => ({}))) as { reason?: string };
        throw new LiveUnavailableError(
          problem.reason === "over_budget" ? "over_budget" : "agent_unavailable",
        );
      }
      default:
        throw new LiveUnavailableError("server_error");
    }
  }

  /** The attempt's trace; null while the trace store is unavailable. */
  async trace(token: string, traceId: string): Promise<Trace | null> {
    const response = await this.request(`/v1/traces/${encodeURIComponent(traceId)}`, {
      method: "GET",
      headers: { Authorization: `Bearer ${token}` },
    });
    return response.ok ? ((await response.json()) as Trace) : null;
  }

  async leaderboard(): Promise<LeaderboardEntry[]> {
    const response = await this.request("/v1/leaderboard", { method: "GET" });
    if (!response.ok) throw new LiveUnavailableError("server_error");
    return ((await response.json()) as { entries: LeaderboardEntry[] }).entries;
  }

  private async request(path: string, init: RequestInit, timeoutMs = TIMEOUT_MS) {
    try {
      return await this.fetchImpl(this.baseUrl + path, {
        ...init,
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch {
      // Network failure, CORS failure or timeout: the backend is asleep or unreachable.
      throw new LiveUnavailableError("unreachable");
    }
  }
}

/**
 * A recorded result for a combo, served as a static file next to the game. Null if this combo
 * has not been recorded (the eval harness records them).
 */
export async function loadReplay(
  level: number,
  comboId: string,
  fetchImpl: typeof fetch = (...args) => fetch(...args),
): Promise<ReplayRecord | null> {
  try {
    const response = await fetchImpl(`/replay/${level}/${comboId}.json`);
    if (!response.ok) return null;
    const record = (await response.json()) as ReplayRecord;
    return record.schemaVersion === 1 && record.comboId === comboId && record.level === level
      ? record
      : null;
  } catch {
    return null;
  }
}
