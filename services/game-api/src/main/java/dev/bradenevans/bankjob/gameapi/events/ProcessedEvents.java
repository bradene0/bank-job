package dev.bradenevans.bankjob.gameapi.events;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes consumers idempotent. Kafka delivers at least once, so a consumer records each event id in
 * the same transaction as its projection update; a redelivered event finds its id and is skipped.
 */
@Repository
public class ProcessedEvents {

  private final JdbcClient jdbc;

  ProcessedEvents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Claims an event for a consumer. True the first time; false if this consumer already processed
   * it. Must run inside the transaction that applies the event, so the claim and the effect commit
   * or roll back together.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean claim(String consumer, UUID eventId) {
    return jdbc.sql(
                """
                INSERT INTO processed_events (consumer, event_id) VALUES (:consumer, :event)
                ON CONFLICT DO NOTHING
                """)
            .param("consumer", consumer)
            .param("event", eventId)
            .update()
        == 1;
  }
}
