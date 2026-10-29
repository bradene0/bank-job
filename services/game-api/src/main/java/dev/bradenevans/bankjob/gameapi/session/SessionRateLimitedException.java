package dev.bradenevans.bankjob.gameapi.session;

public class SessionRateLimitedException extends RuntimeException {

  private final long retryAfterSeconds;

  SessionRateLimitedException(long retryAfterSeconds) {
    super("Too many new sessions from this address; try again later");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
