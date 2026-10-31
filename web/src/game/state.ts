import { SLOT_KEYS, type GameContent, type SlotKey, type TileView } from "../content/types";
import type { Outcome, Span, ToolCall } from "../api/types";

export type Slots = Record<SlotKey, string | null>;

export const EMPTY_SLOTS: Slots = { persona: null, pretext: null, technique: null, payload: null };

/** One played attempt, live or replayed, as the result and "Under the hood" panels show it. */
export interface Played {
  source: "live" | "replay";
  level: number;
  comboId: string;
  outcome: Outcome;
  amountCents: number;
  reply: string;
  toolCalls: ToolCall[];
  /** Live attempts: the trace to fetch. Replays carry their recorded spans instead. */
  traceId: string | null;
  recordedSpans: Span[];
  /** Live attempts: the browser-measured round trip. */
  clientMs: number | null;
  /** Live attempts: game-api replayed today's recorded model decisions (ADR-0012). */
  cached: boolean;
  /** Live attempts: what this one added to the crew's score. Recorded results do not score. */
  points: Points | null;
}

/** One line in the passbook: a wire that reached the player's account. */
export interface PassbookLine {
  level: number;
  amountCents: number;
  /** What the win scored (0 for a repeat win or a recorded result). */
  points: number;
  /** Played from a recording while the bank was closed: shown, never scored. */
  recorded: boolean;
}

/** Points per nerve left when a level is cracked. */
export const NERVE_BONUS = 10;

/** What one attempt added to the score, exactly as the leaderboard counts it. */
export type Points =
  | { kind: "cracked"; level: number; levelPoints: number; nerveBonus: number; total: number }
  | { kind: "already-cracked"; total: 0 }
  | { kind: "none"; total: 0 };

export function comboId(slots: Slots): string | null {
  const ids = SLOT_KEYS.map((key) => slots[key]);
  return ids.every((id): id is string => id !== null) ? ids.join(".") : null;
}

export function filledCount(slots: Slots): number {
  return SLOT_KEYS.filter((key) => slots[key] !== null).length;
}

export function tileById(content: GameContent, id: string): TileView | undefined {
  for (const slot of content.slots) {
    const tile = slot.tiles.find((t) => t.id === id);
    if (tile) return tile;
  }
  return undefined;
}

/** Places a tile in its own slot, replacing whatever was there. Unknown ids change nothing. */
export function place(content: GameContent, slots: Slots, tileId: string): Slots {
  const tile = tileById(content, tileId);
  return tile ? { ...slots, [tile.slot]: tile.id } : slots;
}

export function clear(slots: Slots, key: SlotKey): Slots {
  return { ...slots, [key]: null };
}

/**
 * Scoring (docs/security-and-levels.md), as game-api's leaderboard projection counts it: the
 * session's first win on a level cracks it, for the level's points plus NERVE_BONUS per nerve left
 * on that level after the attempt. Repeat wins and every other outcome score nothing.
 */
export function score(
  content: GameContent,
  level: number,
  outcome: Outcome,
  nerveLeft: number,
  alreadyCracked: boolean,
): Points {
  if (outcome !== "won") return { kind: "none", total: 0 };
  if (alreadyCracked) return { kind: "already-cracked", total: 0 };
  const levelPoints = content.levels.find((l) => l.id === level)?.points ?? 0;
  const nerveBonus = NERVE_BONUS * Math.max(0, nerveLeft);
  return { kind: "cracked", level, levelPoints, nerveBonus, total: levelPoints + nerveBonus };
}

export function formatUsd(cents: number): string {
  return (cents / 100).toLocaleString("en-US", { style: "currency", currency: "USD" });
}
