package dev.bradenevans.bankjob.gameapi.policy;

import java.util.UUID;

/**
 * Asks the guardrail agent about a sensitive tool call. Its verdict is one input to the policy
 * engine, which still makes the decision; any failure must come back as a deny.
 */
@FunctionalInterface
public interface GuardrailReviewer {
  GuardrailVerdict review(UUID attemptId, ToolRequest request);
}
