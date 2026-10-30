package dev.bradenevans.bankjob.gameapi.content;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The seed behind the daily rotation: today's UTC date, or a fixed value for reproducible local
 * runs and evals ({@code DAILY_SEED_OVERRIDE}).
 */
@Component
public class DailySeed {

  private final Clock clock;
  private final String override;

  DailySeed(Clock clock, @Value("${bankjob.game.daily-seed-override:}") String override) {
    if (!override.isEmpty() && !override.matches("[A-Za-z0-9-]{1,32}")) {
      throw new IllegalStateException("DAILY_SEED_OVERRIDE must be 1-32 letters, digits or '-'");
    }
    this.clock = clock;
    this.override = override;
  }

  public String today() {
    return override.isEmpty() ? LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString() : override;
  }
}
