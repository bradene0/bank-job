package dev.bradenevans.bankjob.gameapi.outbox;

import dev.bradenevans.bankjob.gameapi.events.EventType;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional outbox (ADR-0002). An event is a row written in the same transaction as the
 * change it describes, so the two commit or roll back together: no event for a change that did not
 * happen, and no change without its event. {@link OutboxRelay} sends committed rows to Kafka.
 */
@Component
public class Outbox {

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Tracer tracer;
  private final Propagator propagator;
  private final OutboxRelay relay;

  Outbox(
      JdbcClient jdbc, JsonMapper json, Tracer tracer, Propagator propagator, OutboxRelay relay) {
    this.jdbc = jdbc;
    this.json = json;
    this.tracer = tracer;
    this.propagator = propagator;
    this.relay = relay;
  }

  /**
   * Appends an event. MANDATORY: called outside a transaction this fails instead of silently
   * writing an event that could outlive a rolled-back change.
   *
   * @param key the Kafka message key; events with the same key stay in order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      EventType type, String aggregateType, String aggregateId, String key, JsonNode payload) {
    jdbc.sql(
            """
            INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, topic,
                                       message_key, payload, trace_context)
            VALUES (:aggregateType, :aggregateId, :type, :topic, :key, :payload::jsonb,
                    :trace::jsonb)
            """)
        .param("aggregateType", aggregateType)
        .param("aggregateId", aggregateId)
        .param("type", type.wire())
        .param("topic", type.topic())
        .param("key", key)
        .param("payload", json.writeValueAsString(payload))
        .param("trace", traceContext())
        .update();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            relay.wakeUp(); // relay now rather than at the next poll
          }
        });
  }

  /**
   * The current span as W3C headers, so the relay can continue this trace when it publishes the
   * event, possibly after the request that wrote it has finished.
   */
  private String traceContext() {
    var span = tracer.currentSpan();
    if (span == null) {
      return null;
    }
    TraceContext context = span.context();
    Map<String, String> headers = new LinkedHashMap<>();
    propagator.inject(context, headers, Map::put);
    return headers.isEmpty() ? null : json.writeValueAsString(headers);
  }
}
