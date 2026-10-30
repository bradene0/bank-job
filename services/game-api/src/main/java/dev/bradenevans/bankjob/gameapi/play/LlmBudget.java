package dev.bradenevans.bankjob.gameapi.play;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * App-level model spend counter (docs/architecture.md, cost guardrails). Near the monthly budget
 * the game stops calling the model and the client switches to replay mode. The Anthropic
 * workspace's own spend limit is the hard backstop.
 */
@Component
class LlmBudget {

  private final JdbcClient jdbc;
  private final LlmProperties properties;
  private final Clock clock;

  LlmBudget(JdbcClient jdbc, LlmProperties properties, Clock clock) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.clock = clock;
  }

  /** True once this month's estimated spend reaches the cutoff share of the budget. */
  @Transactional(readOnly = true)
  boolean exhausted() {
    LocalDate monthStart = today().withDayOfMonth(1);
    long spentMicros =
        jdbc.sql("SELECT coalesce(sum(est_cost_micros), 0) FROM llm_usage WHERE day >= :start")
            .param("start", monthStart)
            .query(Long.class)
            .single();
    long budgetMicros = properties.monthlyBudgetCents() * 10_000;
    return spentMicros >= (long) (budgetMicros * properties.budgetCutoff());
  }

  @Transactional
  void record(AgentClient.Usage usage) {
    if (usage.inputTokens() == 0 && usage.outputTokens() == 0) {
      return;
    }
    long micros =
        Math.ceilDiv(
            usage.inputTokens() * properties.inputMicrosPerMtok()
                + usage.outputTokens() * properties.outputMicrosPerMtok(),
            1_000_000L);
    jdbc.sql(
            """
            INSERT INTO llm_usage (day, input_tokens, output_tokens, est_cost_micros)
            VALUES (:day, :input, :output, :micros)
            ON CONFLICT (day) DO UPDATE SET
              input_tokens = llm_usage.input_tokens + excluded.input_tokens,
              output_tokens = llm_usage.output_tokens + excluded.output_tokens,
              est_cost_micros = llm_usage.est_cost_micros + excluded.est_cost_micros
            """)
        .param("day", today())
        .param("input", usage.inputTokens())
        .param("output", usage.outputTokens())
        .param("micros", micros)
        .update();
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }
}
