package dev.bradenevans.bankjob.gameapi.policy;

/**
 * The policy engine's answer for one tool call. {@code code} is stable and machine-readable; {@code
 * reason} is shown to Penny (and the player) when a call is denied.
 */
public record PolicyDecision(boolean allowed, String code, String reason, GuardrailVerdict review) {

  static PolicyDecision allow(String reason, GuardrailVerdict review) {
    return new PolicyDecision(true, "allowed", reason, review);
  }

  static PolicyDecision deny(String code, String reason) {
    return new PolicyDecision(false, code, reason, null);
  }

  static PolicyDecision deny(String code, String reason, GuardrailVerdict review) {
    return new PolicyDecision(false, code, reason, review);
  }

  /** The text stored in tool_calls.policy_reason, e.g. "teller_limit: ...". */
  public String recorded() {
    return code + ": " + reason;
  }
}
