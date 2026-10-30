package dev.bradenevans.bankjob.gameapi.play;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How game-api reaches the agent and accounts for model spend.
 *
 * @param mode "stub" or "live"; must match the agent's LLM_MODE (both read the same variable)
 * @param model the model id the agent uses in live mode
 * @param monthlyBudgetCents app-level spend cap; live attempts stop at {@code budgetCutoff} of it
 * @param inputMicrosPerMtok price per million input tokens, in micro-dollars
 * @param outputMicrosPerMtok price per million output tokens, in micro-dollars
 */
@ConfigurationProperties("bankjob.llm")
public record LlmProperties(
    String mode,
    String model,
    long monthlyBudgetCents,
    double budgetCutoff,
    long inputMicrosPerMtok,
    long outputMicrosPerMtok,
    Agent agent) {

  public record Agent(
      String url, Duration connectTimeout, Duration runTimeout, Duration reviewTimeout) {}

  public LlmProperties {
    if (!"stub".equals(mode) && !"live".equals(mode)) {
      throw new IllegalStateException("LLM_MODE must be stub or live, got " + mode);
    }
    if (budgetCutoff <= 0 || budgetCutoff > 1) {
      throw new IllegalStateException("bankjob.llm.budget-cutoff must be in (0, 1]");
    }
  }

  boolean live() {
    return "live".equals(mode);
  }

  /** Part of the response-cache key: stub decisions must never be served in live mode. */
  String cacheModel() {
    return live() ? model : "stub";
  }
}
