import content from "virtual:bankjob-content";
import { describe, expect, it } from "vitest";
import type { LevelView } from "../content/types";
import { EMPTY_SLOTS, clear, comboId, filledCount, place, score } from "./state";

describe("slots", () => {
  it("puts each tile in its own slot and builds the combo id", () => {
    let slots = place(content, EMPTY_SLOTS, "t3");
    slots = place(content, slots, "p2");
    expect(comboId(slots)).toBeNull();
    slots = place(content, place(content, slots, "y4"), "x6");
    expect(slots).toEqual({ persona: "p2", pretext: "x6", technique: "t3", payload: "y4" });
    expect(comboId(slots)).toBe("p2.x6.t3.y4");
  });

  it("replaces the tile in a slot and ignores unknown ids", () => {
    const slots = place(content, place(content, EMPTY_SLOTS, "p1"), "p5");
    expect(slots.persona).toBe("p5");
    expect(place(content, slots, "zz")).toBe(slots);
    expect(filledCount(clear(slots, "persona"))).toBe(0);
  });
});

describe("score", () => {
  it("gives a level's points and 10 per nerve left for the first win on it", () => {
    expect(score(content, 1, "won", 6, false)).toEqual({
      kind: "cracked",
      level: 1,
      levelPoints: 100,
      nerveBonus: 60,
      total: 160,
    });
    expect(score(content, 5, "won", 0, false).total).toBe(500);
  });

  it("gives nothing for a repeat win or a call that fails", () => {
    expect(score(content, 3, "won", 4, true)).toEqual({ kind: "already-cracked", total: 0 });
    expect(score(content, 3, "blocked", 4, false)).toEqual({ kind: "none", total: 0 });
    expect(score(content, 3, "refused", 4, false).total).toBe(0);
  });

  it("tops out at 1,750: every level cracked on its first try", () => {
    const best = content.levels.reduce(
      (sum: number, level: LevelView) => sum + score(content, level.id, "won", level.nerve - 1, false).total,
      0,
    );
    expect(best).toBe(1_750);
  });
});
