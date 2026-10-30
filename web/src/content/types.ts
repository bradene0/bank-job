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
  /** What cracking the level is worth (plus the nerve bonus). */
  points: number;
  lesson: string;
  /** The controls this level enforces, in plain words. The intended gap is never shipped. */
  controls: Control[];
}

/** The control keys in content/levels.yaml. Each one also puts a prop in Penny's window. */
export const CONTROL_KEYS = [
  "system_prompt_only",
  "transfer_limit_cents",
  "require_verified_owner",
  "require_out_of_band_confirmation",
  "protect_contact_changes",
  "anomaly_rules",
  "guardrail_agent_review",
] as const;
export type ControlKey = (typeof CONTROL_KEYS)[number];

export interface Control {
  key: ControlKey;
  text: string;
}

export interface GameContent {
  slots: SlotView[];
  levels: LevelView[];
}
