package dev.bradenevans.bankjob.gameapi.maintenance;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the attempts table partitioned ahead of time: the current month plus the next two always
 * exist. Runs at startup and daily, so a stopped instance catches up when it wakes.
 */
@Component
class AttemptPartitionMaintainer {

  private static final Logger log = LoggerFactory.getLogger(AttemptPartitionMaintainer.class);
  static final int MONTHS_AHEAD = 2;

  private final JdbcClient jdbc;
  private final Clock clock;

  AttemptPartitionMaintainer(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
  void ensurePartitions() {
    LocalDate month = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    for (int i = 0; i <= MONTHS_AHEAD; i++) {
      String partition =
          jdbc.sql("SELECT ensure_attempts_partition(:day)")
              .param("day", month.plusMonths(i))
              .query(String.class)
              .single();
      log.debug("Attempts partition {} is present", partition);
    }
  }
}
