package dev.bradenevans.bankjob.gameapi.attempt;

import dev.bradenevans.bankjob.gameapi.ledger.AttemptRef;
import java.time.Instant;
import java.util.UUID;

/** One attempt row. {@code outcome} is null while Penny is still working on it. */
public record AttemptRecord(
    UUID id,
    Instant createdAt,
    UUID sessionId,
    int level,
    String comboId,
    String dailySeed,
    Outcome outcome) {

  public AttemptRef ref() {
    return new AttemptRef(id, createdAt);
  }

  public boolean inFlight() {
    return outcome == null;
  }
}
