package dev.bradenevans.bankjob.gameapi.audit;

import dev.bradenevans.bankjob.gameapi.events.EventEnvelope;
import dev.bradenevans.bankjob.gameapi.events.EventType;
import dev.bradenevans.bankjob.gameapi.events.ProcessedEvents;
import dev.bradenevans.bankjob.gameapi.events.Topics;
import java.sql.Timestamp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Writes every event to the audit log: who did what to which aggregate. */
@Component
class AuditTrail {

  static final String CONSUMER = "audit";

  private final ProcessedEvents processed;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  AuditTrail(
      ProcessedEvents processed,
      JdbcClient jdbc,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.processed = processed;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.json = json;
  }

  @KafkaListener(
      id = CONSUMER,
      groupId = CONSUMER,
      topics = {Topics.ATTEMPTS, Topics.TOOL_CALLS, Topics.LEDGER, Topics.OUTCOMES})
  void onEvent(ConsumerRecord<String, String> record) {
    EventEnvelope event = EventEnvelope.parse(json, record.value());
    transactions.executeWithoutResult(
        status -> {
          if (!processed.claim(CONSUMER, event.eventId())) {
            return;
          }
          jdbc.sql(
                  """
                  INSERT INTO audit_log (event_id, actor, action, target, details, created_at)
                  VALUES (:event, :actor, :action, :target, :details::jsonb, :at)
                  """)
              .param("event", event.eventId())
              .param("actor", actor(event.type()))
              .param("action", event.type().wire())
              .param("target", target(event))
              .param("details", record.value())
              .param("at", Timestamp.from(event.occurredAt()))
              .update();
        });
  }

  private static String actor(EventType type) {
    return switch (type) {
      case ATTEMPT_SUBMITTED -> "player";
      case TOOL_CALLED -> "penny";
      case POLICY_DECIDED -> "policy-engine";
      case TRANSFER_POSTED -> "ledger";
      case ATTEMPT_RESOLVED -> "game-api";
    };
  }

  private static String target(EventEnvelope event) {
    return event.type() == EventType.TRANSFER_POSTED
        ? "account:" + event.text("to_account_id")
        : "attempt:" + event.text("attempt_id");
  }
}
