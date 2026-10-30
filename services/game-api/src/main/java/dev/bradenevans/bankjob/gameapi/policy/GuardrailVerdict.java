package dev.bradenevans.bankjob.gameapi.policy;

/** The Level 5 guardrail agent's view of one proposed tool call. */
public record GuardrailVerdict(boolean allow, String reason, String model) {

  /** Used whenever the guardrail cannot answer: fail closed. */
  public static GuardrailVerdict unavailable(String why) {
    return new GuardrailVerdict(false, "guardrail unavailable: " + why, "none");
  }
}
