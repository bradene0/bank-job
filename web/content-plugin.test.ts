// @vitest-environment node
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { loadContent } from "./content-plugin.ts";

const CONTENT = path.resolve(import.meta.dirname, "../content");

describe("loadContent", () => {
  const content = loadContent(CONTENT);

  it("ships every tile, in slot order, with 6 x 6 x 6 x 4 tiles", () => {
    expect(content.slots.map((s) => s.key)).toEqual(["persona", "pretext", "technique", "payload"]);
    expect(content.slots.map((s) => s.tiles.length)).toEqual([6, 6, 6, 4]);
    expect(content.levels.map((l) => l.id)).toEqual([1, 2, 3, 4, 5]);
  });

  it("never ships a prompt fragment or a level's intended gap (ADR-0003)", () => {
    const shipped = JSON.stringify(content);
    const tiles = parse(readFileSync(path.join(CONTENT, "tiles.yaml"), "utf8"));
    const fragments = Object.values(tiles.slots).flatMap((slot) =>
      (slot as { tiles: { fragment: string }[] }).tiles.map((t) => t.fragment),
    );
    expect(fragments).toHaveLength(22);
    for (const fragment of fragments) expect(shipped).not.toContain(fragment);
    expect(shipped).not.toContain("fragment");
    expect(shipped).not.toContain("security-question hint");
    expect(shipped).not.toContain("not behind step-up");
  });

  it("describes each level's controls in plain words", () => {
    expect(content.levels[0]?.controls).toEqual([
      { key: "system_prompt_only", text: "Penny's instructions (a system prompt) and nothing else" },
    ]);
    expect(content.levels[1]?.controls).toEqual([
      { key: "transfer_limit_cents", text: "Teller limit: $1,000 per call, enforced in code" },
    ]);
    expect(content.levels[4]?.controls.map((c) => c.key)).toEqual([
      "transfer_limit_cents",
      "require_verified_owner",
      "require_out_of_band_confirmation",
      "protect_contact_changes",
      "anomaly_rules",
      "guardrail_agent_review",
    ]);
  });

  it("fails the build on a control it cannot describe", () => {
    const dir = mkdtempSync(path.join(tmpdir(), "content-"));
    writeFileSync(path.join(dir, "tiles.yaml"), readFileSync(path.join(CONTENT, "tiles.yaml")));
    writeFileSync(
      path.join(dir, "levels.yaml"),
      "levels:\n  - {id: 1, name: X, nerve: 1, points: 100, lesson: L, controls: {secret_sauce: true}}\n",
    );
    expect(() => loadContent(dir)).toThrow(/secret_sauce is not a known control/);
  });
});
