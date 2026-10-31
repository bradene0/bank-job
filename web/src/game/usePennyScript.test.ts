import { describe, expect, it } from "vitest";
import type { ToolCall } from "../api/types";
import { beatFor, stepFor } from "./usePennyScript";

const call = (tool: string, decision: "allow" | "deny" = "allow", review: ToolCall["review"] = null): ToolCall => ({
  ordinal: 1,
  tool,
  args: {},
  decision,
  reason: decision === "deny" ? "teller_limit: over the limit" : "allowed",
  result: {},
  review,
  latencyMs: 1,
});

describe("Penny's beats", () => {
  it("gives each bank tool its own pose", () => {
    expect(beatFor(call("verify_identity")).pose).toBe("verify");
    expect(beatFor(call("transfer_funds")).pose).toBe("transfer");
    expect(beatFor(call("update_contact_info")).pose).toBe("phone");
  });

  it("says when the bank's controls stop her, and when the guardrail reviewed the call", () => {
    const denied = beatFor(call("transfer_funds", "deny", { verdict: "deny" }));
    expect(denied.decision).toBe("deny");
    expect(denied.line).toMatch(/controls won't let me/);
    expect(denied.reviewing).toBe(true);
  });

  it("falls back to thinking for a tool it does not know", () => {
    expect(beatFor(call("open_vault_door")).pose).toBe("thinking");
  });

  it("keeps the whole performance to about three seconds", () => {
    expect(stepFor(1)).toBe(900);
    expect(stepFor(8)).toBe(500);
    expect(stepFor(4)).toBe(750);
  });
});
