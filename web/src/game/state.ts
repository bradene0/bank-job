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
}

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

/** Points as the leaderboard counts them: dollars stolen times the level's multiplier, minus 1. */
export function score(content: GameContent, played: Played): number {
  const level = content.levels.find((l) => l.id === played.level);
  const stolen = played.outcome === "won" ? Math.floor(played.amountCents / 100) : 0;
  return stolen * (level?.multiplier ?? 1) - 1;
}

export function formatUsd(cents: number): string {
  return (cents / 100).toLocaleString("en-US", { style: "currency", currency: "USD" });
}
