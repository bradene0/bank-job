package dev.bradenevans.bankjob.gameapi.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionCreationLimiterTest {

  /** A clock the test can move forward. */
  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-29T10:15:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  final MutableClock clock = new MutableClock();
  final SessionCreationLimiter limiter =
      new SessionCreationLimiter(new SessionProperties(2, null, false), clock);

  @Test
  void allowsUpToTheLimitPerAddressPerWindow() {
    assertThat(limiter.tryAcquire("ip-a")).isTrue();
    assertThat(limiter.tryAcquire("ip-a")).isTrue();
    assertThat(limiter.tryAcquire("ip-a")).isFalse();
    assertThat(limiter.tryAcquire("ip-b")).isTrue(); // counters are per address
  }

  @Test
  void resetsInTheNextWindowAndReportsTimeUntilThen() {
    limiter.tryAcquire("ip-a");
    limiter.tryAcquire("ip-a");
    assertThat(limiter.secondsUntilReset()).isEqualTo(Duration.ofMinutes(45).toSeconds());

    clock.now = clock.now.plus(Duration.ofMinutes(45));

    assertThat(limiter.tryAcquire("ip-a")).isTrue();
  }
}
