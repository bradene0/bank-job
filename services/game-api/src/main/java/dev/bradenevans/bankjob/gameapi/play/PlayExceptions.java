package dev.bradenevans.bankjob.gameapi.play;

/** Why an attempt could not be played. Mapped to problem details by ApiExceptionHandler. */
public final class PlayExceptions {

  private PlayExceptions() {}

  /** The request was not exactly {level, tiles} with allowlisted tile ids. */
  public static class InvalidAttemptException extends RuntimeException {
    InvalidAttemptException(String message) {
      super(message);
    }
  }

  /** No nerve left for this level, or the session has expired. */
  public static class NerveExhaustedException extends RuntimeException {
    NerveExhaustedException(int level) {
      super("No nerve left for level " + level + " in this session");
    }
  }

  /** The session already has an attempt in flight. */
  public static class AttemptInProgressException extends RuntimeException {
    AttemptInProgressException() {
      super("Another attempt from this session is still running");
    }
  }

  /** Live play is unavailable (agent down, or over the model budget): use replay mode. */
  public static class LivePlayUnavailableException extends RuntimeException {
    private final String reason;

    LivePlayUnavailableException(String reason, String message) {
      super(message);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }
}
