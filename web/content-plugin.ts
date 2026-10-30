import { readFileSync } from "node:fs";
import path from "node:path";
import { parse } from "yaml";
import type { Plugin } from "vite";
import {
  SLOT_KEYS,
  type GameContent,
  type LevelView,
  type SlotKey,
  type SlotView,
} from "./src/content/types.ts";

// Bundles content/tiles.yaml and content/levels.yaml into the app as `virtual:bankjob-content`.
// The repo's content/ directory is the single source of truth (game-api reads the same files).
// Prompt fragments are dropped here, so they never reach the browser (ADR-0003).

const MODULE_ID = "virtual:bankjob-content";
const RESOLVED_ID = "\0" + MODULE_ID;

const TILE_KEYS = new Set(["id", "label", "blurb", "fragment", "amount_cents", "attaches_document"]);
const LEVEL_KEYS = new Set(["id", "name", "nerve", "points", "controls", "intended_gap", "lesson"]);

/** Plain-language names for level controls; an unknown control fails the build. */
const CONTROL_TEXT: Record<string, (value: unknown) => string> = {
  system_prompt_only: () => "Penny's instructions (a system prompt) and nothing else",
  transfer_limit_cents: (cents) =>
    `Teller limit: $${(Number(cents) / 100).toLocaleString("en-US")} per call, enforced in code`,
  require_verified_owner: () => "The caller must verify as the account's owner",
  require_out_of_band_confirmation: () => "A confirmation text goes to the owner's phone",
  protect_contact_changes: () => "Phone number changes need confirmation too",
  anomaly_rules: () => "Velocity rules fed by the event stream",
  guardrail_agent_review: () => "A second AI reviews every sensitive request",
};

function fail(message: string): never {
  throw new Error(`content: ${message}`);
}

function record(value: unknown, where: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    fail(`${where} must be a mapping`);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, where: string): string {
  if (typeof value !== "string" || value.trim() === "") fail(`${where} must be a string`);
  return value;
}

function unknownKeys(entry: Record<string, unknown>, allowed: Set<string>, where: string) {
  const extra = Object.keys(entry).filter((key) => !allowed.has(key));
  if (extra.length > 0) fail(`${where} has unknown keys: ${extra.join(", ")}`);
}

export function loadContent(contentDir: string): GameContent {
  const tiles = record(parse(readFileSync(path.join(contentDir, "tiles.yaml"), "utf8")), "tiles.yaml");
  const levels = record(parse(readFileSync(path.join(contentDir, "levels.yaml"), "utf8")), "levels.yaml");
  const slotsYaml = record(tiles.slots, "slots");

  const slots: SlotView[] = SLOT_KEYS.map((key: SlotKey) => {
    const slot = record(slotsYaml[key], `slots.${key}`);
    const list = slot.tiles;
    if (!Array.isArray(list) || list.length === 0) fail(`slots.${key}.tiles must be a list`);
    return {
      key,
      label: text(slot.label, `slots.${key}.label`),
      tiles: list.map((raw, i) => {
        const where = `slots.${key}.tiles[${i}]`;
        const tile = record(raw, where);
        unknownKeys(tile, TILE_KEYS, where);
        return {
          id: text(tile.id, `${where}.id`),
          slot: key,
          label: text(tile.label, `${where}.label`),
          blurb: tile.blurb === undefined ? null : text(tile.blurb, `${where}.blurb`),
          ...(key === "payload" ? { amountCents: (tile.amount_cents as number | null) ?? null } : {}),
        };
      }),
    };
  });

  const levelList = levels.levels;
  if (!Array.isArray(levelList)) fail("levels must be a list");
  const levelViews: LevelView[] = levelList.map((raw, i) => {
    const where = `levels[${i}]`;
    const level = record(raw, where);
    unknownKeys(level, LEVEL_KEYS, where);
    const controls = record(level.controls, `${where}.controls`);
    return {
      id: Number(level.id),
      name: text(level.name, `${where}.name`),
      nerve: Number(level.nerve),
      points: Number(level.points),
      lesson: text(level.lesson, `${where}.lesson`),
      // intended_gap is deliberately left out: it is the level's answer.
      controls: Object.entries(controls).map(([name, value]) => {
        const describe = CONTROL_TEXT[name];
        if (!describe) fail(`${where}.controls.${name} is not a known control`);
        return describe(value);
      }),
    };
  });

  return { slots, levels: levelViews };
}

export function bankjobContent(contentDir = process.env.BANKJOB_CONTENT_DIR): Plugin {
  const dir = contentDir ?? path.resolve(import.meta.dirname, "../content");
  return {
    name: "bankjob-content",
    resolveId: (id) => (id === MODULE_ID ? RESOLVED_ID : undefined),
    load(id) {
      if (id !== RESOLVED_ID) return undefined;
      this.addWatchFile(path.join(dir, "tiles.yaml"));
      this.addWatchFile(path.join(dir, "levels.yaml"));
      return `export default ${JSON.stringify(loadContent(dir))};`;
    },
  };
}
