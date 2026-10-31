import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiClient,
  LiveUnavailableError,
  NerveExhaustedError,
  SessionExpiredError,
  loadReplay,
} from "../api/client";
import type { Session } from "../api/types";
import { SLOT_KEYS, type GameContent, type SlotKey } from "../content/types";
import {
  EMPTY_SLOTS,
  clear,
  comboId,
  place,
  score,
  type PassbookLine,
  type Played,
  type Slots,
} from "./state";
import type { BackendStatus } from "./useBackendStatus";

const SESSION_KEY = "bankjob.session";
const PROGRESS_KEY = "bankjob.progress";
const CRACKED_KEY = "bankjob.cracked";
const PASSBOOK_KEY = "bankjob.passbook";

/** Levels the live session has cracked: they score once per session, like the leaderboard. */
interface Cracked {
  sessionId: string;
  levels: number[];
}

interface Progress {
  beaten: number[];
  /** Attempts left per level for recorded play; live play uses the session's nerve. */
  replayNerve: Record<string, number>;
}

// Storage can throw (private mode, blocked site data); the game then simply forgets on reload.
function readJson<T>(key: string): T | null {
  try {
    const raw = sessionStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function writeJson(key: string, value: unknown) {
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    // not persisted; fine
  }
}

function initialNerve(content: GameContent): Record<string, number> {
  return Object.fromEntries(content.levels.map((l) => [String(l.id), l.nerve]));
}

function storedSession(): Session | null {
  const session = readJson<Session>(SESSION_KEY);
  // Leave a minute of slack so an attempt never starts with a token about to expire.
  return session && Date.parse(session.expiresAt) - Date.now() > 60_000 ? session : null;
}

export type Notice =
  | { kind: "not-recorded"; comboId: string }
  | { kind: "no-nerve"; level: number }
  | { kind: "replaying"; reason: LiveUnavailableError["reason"] };

export function useGame(
  content: GameContent,
  api: ApiClient,
  backend: { status: BackendStatus; markUnreachable: () => void },
) {
  const [levelId, setLevelId] = useState(1);
  const [slots, setSlots] = useState<Slots>(EMPTY_SLOTS);
  const [played, setPlayed] = useState<Played | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [playing, setPlaying] = useState(false);
  const [session, setSession] = useState<Session | null>(storedSession);
  const [progress, setProgress] = useState<Progress>(
    () => readJson<Progress>(PROGRESS_KEY) ?? { beaten: [], replayNerve: initialNerve(content) },
  );
  const [cracked, setCracked] = useState<Cracked | null>(() => readJson<Cracked>(CRACKED_KEY));
  const [passbook, setPassbook] = useState<PassbookLine[]>(() => readJson<PassbookLine[]>(PASSBOOK_KEY) ?? []);
  const sessionRequest = useRef<Promise<Session> | null>(null);

  useEffect(() => writeJson(PROGRESS_KEY, progress), [progress]);
  useEffect(() => writeJson(PASSBOOK_KEY, passbook), [passbook]);
  useEffect(() => {
    if (cracked) writeJson(CRACKED_KEY, cracked);
  }, [cracked]);
  useEffect(() => {
    if (session) writeJson(SESSION_KEY, session);
  }, [session]);

  const live = backend.status === "live";
  const { markUnreachable } = backend;

  const ensureSession = useCallback(
    async (fresh = false): Promise<Session> => {
      if (session && !fresh) return session;
      sessionRequest.current ??= api.createSession().finally(() => {
        sessionRequest.current = null;
      });
      const created = await sessionRequest.current;
      setSession(created);
      return created;
    },
    [api, session],
  );

  // Show the session's real nerve as soon as the bank is open.
  useEffect(() => {
    if (live && !session) {
      ensureSession().catch((e: unknown) => {
        if (e instanceof LiveUnavailableError && e.reason === "unreachable") markUnreachable();
      });
    }
  }, [live, session, ensureSession, markUnreachable]);

  const nerve = live && session ? session.nerve : progress.replayNerve;

  const placeTile = useCallback(
    (tileId: string) => setSlots((current) => place(content, current, tileId)),
    [content],
  );
  const clearSlot = useCallback((key: SlotKey) => setSlots((current) => clear(current, key)), []);

  // A new level is a new visit to the window: Penny greets the player again.
  const chooseLevel = useCallback((id: number) => {
    setLevelId(id);
    setPlayed(null);
    setNotice(null);
  }, []);

  const won = useCallback((level: number) => {
    setProgress((p) => (p.beaten.includes(level) ? p : { ...p, beaten: [...p.beaten, level] }));
  }, []);

  const playRecorded = useCallback(
    async (combo: string, reason: LiveUnavailableError["reason"] | null) => {
      if ((progress.replayNerve[String(levelId)] ?? 0) <= 0) {
        setPlayed(null);
        setNotice({ kind: "no-nerve", level: levelId });
        return;
      }
      const record = await loadReplay(levelId, combo);
      if (!record) {
        setPlayed(null);
        setNotice({ kind: "not-recorded", comboId: combo });
        return; // nothing was played, so no nerve is spent
      }
      setProgress((p) => ({
        ...p,
        replayNerve: { ...p.replayNerve, [String(levelId)]: (p.replayNerve[String(levelId)] ?? 1) - 1 },
      }));
      if (reason) setNotice({ kind: "replaying", reason });
      setPlayed({
        source: "replay",
        level: levelId,
        comboId: combo,
        outcome: record.outcome,
        amountCents: record.amountCents,
        reply: record.reply,
        toolCalls: record.toolCalls,
        traceId: null,
        recordedSpans: record.spans,
        clientMs: null,
        cached: false,
        points: null,
      });
      if (record.outcome === "won") {
        won(levelId);
        setPassbook((p) => [...p, { level: levelId, amountCents: record.amountCents, points: 0, recorded: true }]);
      }
    },
    [levelId, progress.replayNerve, won],
  );

  const playLive = useCallback(
    async (combo: string, tiles: Record<SlotKey, string>) => {
      let current = await ensureSession();
      let attempt;
      try {
        attempt = await api.attempt(current.token, levelId, tiles);
      } catch (e) {
        if (!(e instanceof SessionExpiredError)) throw e;
        current = await ensureSession(true);
        attempt = await api.attempt(current.token, levelId, tiles);
      }
      const { result, clientMs } = attempt;
      setSession({ ...current, nerve: result.nerveRemaining });
      const crackedLevels = cracked?.sessionId === current.sessionId ? cracked.levels : [];
      const points = score(
        content,
        levelId,
        result.outcome,
        result.nerveRemaining[String(levelId)] ?? 0,
        crackedLevels.includes(levelId),
      );
      if (points.kind === "cracked") {
        setCracked({ sessionId: current.sessionId, levels: [...crackedLevels, levelId] });
      }
      setPlayed({
        source: "live",
        level: levelId,
        comboId: combo,
        outcome: result.outcome,
        amountCents: result.amountCents,
        reply: result.reply,
        toolCalls: result.toolCalls,
        traceId: result.traceId,
        recordedSpans: [],
        clientMs,
        cached: result.cached,
        points,
      });
      if (result.outcome === "won") {
        won(levelId);
        setPassbook((p) => [
          ...p,
          { level: levelId, amountCents: result.amountCents, points: points.total, recorded: false },
        ]);
      }
    },
    [api, content, cracked, ensureSession, levelId, won],
  );

  const makeTheCall = useCallback(async () => {
    const combo = comboId(slots);
    if (!combo || playing) return;
    const tiles = Object.fromEntries(SLOT_KEYS.map((k) => [k, slots[k]])) as Record<SlotKey, string>;
    setPlaying(true);
    setNotice(null);
    try {
      if (!live) {
        await playRecorded(combo, null);
        return;
      }
      try {
        await playLive(combo, tiles);
      } catch (e) {
        if (e instanceof NerveExhaustedError) {
          setPlayed(null);
          setNotice({ kind: "no-nerve", level: levelId });
        } else if (e instanceof LiveUnavailableError) {
          // The game never dead-ends: play the recorded result instead.
          if (e.reason === "unreachable") markUnreachable();
          await playRecorded(combo, e.reason);
        } else {
          throw e;
        }
      }
    } finally {
      setPlaying(false);
    }
  }, [slots, playing, live, playRecorded, playLive, levelId, markUnreachable]);

  return {
    levelId,
    chooseLevel,
    slots,
    placeTile,
    clearSlot,
    clearAll: useCallback(() => setSlots(EMPTY_SLOTS), []),
    makeTheCall,
    playing,
    played,
    notice,
    nerve,
    beaten: progress.beaten,
    passbook,
    session: live ? session : null,
  };
}
