package dev.bradenevans.bankjob.gameapi.anomaly;

import dev.bradenevans.bankjob.gameapi.events.EventEnvelope;
import dev.bradenevans.bankjob.gameapi.events.EventType;
import dev.bradenevans.bankjob.gameapi.events.ProcessedEvents;
import dev.bradenevans.bankjob.gameapi.events.Topics;
import java.sql.Timestamp;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anomaly scoring: turns TransferPosted events into per-account signals (teller credits, when the
 * last one landed). The Level 5 velocity rule reads them through {@link AnomalySignals}.
 */
@Component
class AnomalyScorer {

  static final String CONSUMER = "anomaly-scoring";

  private final ProcessedEvents processed;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  AnomalyScorer(
      ProcessedEvents processed,
      JdbcClient jdbc,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.processed = processed;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.json = json;
  }

  @KafkaListener(id = CONSUMER, groupId = CONSUMER, topics = Topics.LEDGER)
  void onLedgerEvent(ConsumerRecord<String, String> record) {
    EventEnvelope event = EventEnvelope.parse(json, record.value());
    transactions.executeWithoutResult(
        status -> {
          if (!processed.claim(CONSUMER, event.eventId())) {
            return; // a redelivery: already counted
          }
          if (event.type() != EventType.TRANSFER_POSTED
              || event.optionalUuid("attempt_id") == null) {
            return; // only teller transfers (made during an attempt) are signals
          }
          record(event);
        });
  }

  /** Sum, count and max commute, so redelivered or reordered events cannot skew the signals. */
  private void record(EventEnvelope transfer) {
    UUID account = transfer.uuid("to_account_id");
    jdbc.sql(
            """
            INSERT INTO account_signals (account_id, session_id, teller_credits,
                                         teller_credit_cents, last_teller_credit_at)
            VALUES (:account, :session, 1, :amount, :at)
            ON CONFLICT (account_id) DO UPDATE SET
              teller_credits = account_signals.teller_credits + 1,
              teller_credit_cents = account_signals.teller_credit_cents
                                    + excluded.teller_credit_cents,
              last_teller_credit_at = greatest(account_signals.last_teller_credit_at,
                                               excluded.last_teller_credit_at),
              updated_at = now()
            """)
        .param("account", account)
        .param("session", transfer.uuid("session_id"))
        .param("amount", transfer.number("amount_cents"))
        .param("at", Timestamp.from(transfer.occurredAt()))
        .update();
  }
}
