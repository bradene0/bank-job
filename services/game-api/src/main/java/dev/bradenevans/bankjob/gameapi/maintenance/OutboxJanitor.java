package dev.bradenevans.bankjob.gameapi.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes outbox rows a day after they were published; Kafka holds the events from then on. A day
 * is far longer than the Level 5 velocity window, which is the only reader of old rows (the
 * staleness fence in AnomalySignals).
 */
@Component
class OutboxJanitor {

  private static final Logger log = LoggerFactory.getLogger(OutboxJanitor.class);

  private final JdbcClient jdbc;

  OutboxJanitor(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Scheduled(cron = "0 45 * * * *", zone = "UTC")
  void deletePublished() {
    int deleted =
        jdbc.sql(
                """
                DELETE FROM outbox_events
                WHERE published_at IS NOT NULL AND published_at < now() - interval '1 day'
                """)
            .update();
    if (deleted > 0) {
      log.info("Deleted {} published outbox rows", deleted);
    }
  }
}
