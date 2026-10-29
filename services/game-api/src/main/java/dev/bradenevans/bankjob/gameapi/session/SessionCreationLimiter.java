package dev.bradenevans.bankjob.gameapi.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed one-hour window per hashed IP. In memory is enough: game-api runs as a single instance, and
 * a restart resetting the counters is harmless.
 */
@Component
class SessionCreationLimiter {

  static final Duration WINDOW = Duration.ofHours(1);

  private record Window(long index, int count) {}

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final int limit;
  private final Clock clock;

  SessionCreationLimiter(SessionProperties properties, Clock clock) {
    this.limit = properties.maxPerIpPerHour();
    this.clock = clock;
  }

  /** Records an attempt; returns false if the caller is over the limit. */
  boolean tryAcquire(String ipHash) {
    long index = currentWindow();
    Window updated =
        windows.compute(
            ipHash,
            (k, w) ->
                (w == null || w.index() != index)
                    ? new Window(index, 1)
                    : new Window(index, w.count() + 1));
    return updated.count() <= limit;
  }

  /** Seconds until the current window ends, for the Retry-After header. */
  long secondsUntilReset() {
    long windowMillis = WINDOW.toMillis();
    long now = clock.millis();
    return Math.max(1, ((currentWindow() + 1) * windowMillis - now + 999) / 1000);
  }

  @Scheduled(fixedRate = 10, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
  void evictExpired() {
    long index = currentWindow();
    windows.values().removeIf(w -> w.index() < index);
  }

  private long currentWindow() {
    return Instant.now(clock).toEpochMilli() / WINDOW.toMillis();
  }
}
