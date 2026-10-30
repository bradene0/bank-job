import content from "virtual:bankjob-content";
import { describe, expect, it } from "vitest";
import { EMPTY_SLOTS, clear, comboId, filledCount, place, score, type Played } from "./state";

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
  const played = (level: number, outcome: Played["outcome"], amountCents: number): Played => ({
    source: "live",
    level,
    comboId: "p1.x1.t3.y1",
    outcome,
    amountCents,
    reply: "",
    toolCalls: [],
    traceId: null,
    recordedSpans: [],
    clientMs: null,
    cached: false,
  });

  it("is dollars times the level multiplier, minus one per attempt", () => {
    expect(score(content, played(1, "won", 20_000))).toBe(199);
    expect(score(content, played(3, "won", 100_000))).toBe(2_999);
    expect(score(content, played(5, "blocked", 0))).toBe(-1);
  });
});
