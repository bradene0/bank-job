package dev.bradenevans.bankjob.gameapi.anomaly;

import dev.bradenevans.bankjob.gameapi.events.Topics;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What anomaly scoring knows about an account, for the Level 5 rules.
 *
 * <p>The signals are fed asynchronously through Kafka, so they can lag the ledger. A security rule
 * must not read a lagging signal as "nothing happened", so each read also reports whether any
 * ledger event for the account is still unprocessed: in the outbox and not yet claimed by the
 * scorer. The policy engine denies while that is true (fail closed) instead of guessing.
 */
@Service
public class AnomalySignals {

  /**
   * @param lastTellerCreditAt when a teller transfer last credited the account, or null if never
   * @param stale true if the scorer has not processed every ledger event for the account yet
   */
  public record CreditSignal(Instant lastTellerCreditAt, boolean stale) {}

  private final JdbcClient jdbc;

  AnomalySignals(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public CreditSignal credits(UUID accountId) {
    return jdbc.sql(
            """
            SELECT
              (SELECT last_teller_credit_at FROM account_signals WHERE account_id = :account)
                AS last_credit,
              EXISTS (
                SELECT 1 FROM outbox_events o
                WHERE o.topic = :topic AND o.message_key = :key
                  AND NOT EXISTS (SELECT 1 FROM processed_events p
                                  WHERE p.consumer = :consumer AND p.event_id = o.id)
              ) AS stale
            """)
        .param("account", accountId)
        .param("topic", Topics.LEDGER)
        .param("key", accountId.toString())
        .param("consumer", AnomalyScorer.CONSUMER)
        .query(
            (rs, row) -> {
              Timestamp last = rs.getTimestamp("last_credit");
              return new CreditSignal(
                  last == null ? null : last.toInstant(), rs.getBoolean("stale"));
            })
        .single();
  }
}
