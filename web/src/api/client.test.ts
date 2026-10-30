import { describe, expect, it } from "vitest";
import { fakeBackend, json, replayOf } from "../test/fakeBackend";
import {
  ApiClient,
  LiveUnavailableError,
  NerveExhaustedError,
  SessionExpiredError,
  loadReplay,
} from "./client";

const TILES = { persona: "p1", pretext: "x1", technique: "t3", payload: "y1" };

describe("ApiClient.attempt", () => {
  it("sends exactly a level and tile ids, with the browser's traceparent", async () => {
    const backend = fakeBackend();
    const api = new ApiClient("http://api.test", backend.fetchImpl);

    const { result, clientMs } = await api.attempt("token-1", 1, TILES);

    expect(result.outcome).toBe("won");
    expect(clientMs).toBeGreaterThanOrEqual(0);
    const sent = backend.requests.at(-1);
    expect(sent?.url).toBe("http://api.test/v1/attempts");
    expect(JSON.parse(sent?.body ?? "null")).toEqual({ level: 1, tiles: TILES });
    expect(sent?.headers.authorization).toBe("Bearer token-1");
    expect(sent?.headers.traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
  });

  it.each([
    [json(503, { reason: "agent_unavailable", replay: true }), "agent_unavailable"],
    [json(503, { reason: "over_budget", replay: true }), "over_budget"],
    [json(500, {}), "server_error"],
  ])("maps a failure to replay mode (%#)", async (response, reason) => {
    const api = new ApiClient("", fakeBackend({ attempt: () => response }).fetchImpl);
    await expect(api.attempt("t", 1, TILES)).rejects.toEqual(new LiveUnavailableError(reason as never));
  });

  it("tells an expired session and an exhausted level apart", async () => {
    const expired = new ApiClient("", fakeBackend({ attempt: () => json(401, {}) }).fetchImpl);
    await expect(expired.attempt("t", 1, TILES)).rejects.toBeInstanceOf(SessionExpiredError);
    const tired = new ApiClient("", fakeBackend({ attempt: () => json(429, {}) }).fetchImpl);
    await expect(tired.attempt("t", 1, TILES)).rejects.toBeInstanceOf(NerveExhaustedError);
  });

  it("treats an unreachable backend as asleep", async () => {
    const backend = fakeBackend({ up: false });
    const api = new ApiClient("", backend.fetchImpl);
    await expect(api.attempt("t", 1, TILES)).rejects.toMatchObject({ reason: "unreachable" });
    await expect(api.healthy()).resolves.toBe(false);
  });
});

describe("loadReplay", () => {
  it("returns a recording only if it is the one asked for", async () => {
    const backend = fakeBackend({ replays: { "p1.x1.t3.y1": replayOf("p1.x1.t3.y1", "won") } });
    await expect(loadReplay(1, "p1.x1.t3.y1", backend.fetchImpl)).resolves.toMatchObject({ outcome: "won" });
    await expect(loadReplay(1, "p2.x1.t3.y1", backend.fetchImpl)).resolves.toBeNull();
    await expect(loadReplay(2, "p1.x1.t3.y1", backend.fetchImpl)).resolves.toBeNull();
  });
});

describe("ApiClient.createSession", () => {
  it("reports a session rate limit as such, not as an unreachable bank", async () => {
    const backend = fakeBackend();
    const limited = async (input: RequestInfo | URL, init?: RequestInit) =>
      String(input).endsWith("/v1/sessions") ? json(429, { status: 429 }) : backend.fetchImpl(input, init);
    await expect(new ApiClient("", limited).createSession()).rejects.toMatchObject({ reason: "rate_limited" });
  });
});
