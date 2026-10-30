// The game content the browser gets: tile labels and level rules, never the prompt fragments
// (those stay in game-api, which assembles Penny's prompt from tile ids).

export const SLOT_KEYS = ["persona", "pretext", "technique", "payload"] as const;
export type SlotKey = (typeof SLOT_KEYS)[number];

export interface TileView {
  id: string;
  slot: SlotKey;
  label: string;
  blurb: string | null;
  /** Payload tiles only; null means "the whole vault". */
  amountCents?: number | null;
}

export interface SlotView {
  key: SlotKey;
  label: string;
  tiles: TileView[];
}

export interface LevelView {
  id: number;
  name: string;
  nerve: number;
  multiplier: number;
  lesson: string;
  /** The controls this level enforces, in plain words. The intended gap is never shipped. */
  controls: string[];
}

export interface GameContent {
  slots: SlotView[];
  levels: LevelView[];
}
