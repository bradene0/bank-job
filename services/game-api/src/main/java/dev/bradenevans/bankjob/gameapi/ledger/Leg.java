package dev.bradenevans.bankjob.gameapi.ledger;

import java.util.UUID;

/** One side of a journal entry: debit (negative) or credit (positive) in cents. */
public record Leg(UUID accountId, long amountCents) {

  public Leg {
    if (accountId == null) {
      throw new IllegalArgumentException("accountId is required");
    }
    if (amountCents == 0) {
      throw new IllegalArgumentException("A leg cannot be zero");
    }
  }
}
