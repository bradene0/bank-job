import { useCallback, useEffect, useRef, useState } from "react";
import type { ApiClient } from "../api/client";

/**
 * live: the API answers, attempts run for real.
 * waking: it does not answer yet; the game plays recorded results meanwhile and keeps checking.
 * asleep: still nothing after the wake window; checks continue, less often.
 */
export type BackendStatus = "checking" | "live" | "waking" | "asleep";

export interface BackendTiming {
  /** How often to check while waking. */
  wakingPollMs: number;
  /** How long to wait for a wake before calling the backend asleep (the target is < 2 min). */
  wakeWindowMs: number;
  /** How often to check once asleep. */
  asleepPollMs: number;
}

export const DEFAULT_TIMING: BackendTiming = {
  wakingPollMs: 5_000,
  wakeWindowMs: 150_000,
  asleepPollMs: 30_000,
};

export function useBackendStatus(
  api: ApiClient,
  wake: (() => Promise<void>) | null,
  timing: BackendTiming = DEFAULT_TIMING,
) {
  const [status, setStatus] = useState<BackendStatus>("checking");
  const [generation, setGeneration] = useState(0);
  const wakeRef = useRef(wake);
  useEffect(() => {
    wakeRef.current = wake;
  }, [wake]);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const startedAt = Date.now();
    let wakeRequested = false;

    const check = async () => {
      const up = await api.healthy();
      if (cancelled) return;
      if (up) {
        setStatus("live");
        return;
      }
      if (!wakeRequested && wakeRef.current) {
        wakeRequested = true;
        // Starting the instance is idempotent and rate-limited server-side; failures just mean
        // the next check tries again.
        wakeRef.current().catch(() => undefined);
      }
      const waking = Date.now() - startedAt < timing.wakeWindowMs;
      setStatus(waking ? "waking" : "asleep");
      timer = setTimeout(check, waking ? timing.wakingPollMs : timing.asleepPollMs);
    };
    void check();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [api, generation, timing]);

  /** An attempt could not reach the API: go back to waking and checking. */
  const markUnreachable = useCallback(() => {
    setStatus((current) => (current === "live" ? "waking" : current));
    setGeneration((g) => g + 1);
  }, []);

  return { status, markUnreachable };
}
