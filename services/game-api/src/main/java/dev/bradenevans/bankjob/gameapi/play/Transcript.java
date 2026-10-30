package dev.bradenevans.bankjob.gameapi.play;

import dev.bradenevans.bankjob.gameapi.policy.GuardrailVerdict;
import java.util.List;
import java.util.Map;

/**
 * What the model decided in one attempt: its tool calls in order (with any guardrail verdicts) and
 * its reply. Never what the bank allowed; a cache hit re-runs these calls through the policy engine
 * and the current session's ledger (ADR-0012).
 */
record Transcript(List<Call> calls, String reply, String stopReason) {

  record Call(String tool, Map<String, Object> args, GuardrailVerdict review) {}
}
