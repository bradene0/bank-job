package dev.bradenevans.bankjob.gameapi.ledger;

import java.util.UUID;

/**
 * Outcome of a transfer. A rejection is a normal result, not an exception, so it can be stored
 * under an idempotency key and replayed exactly like a success.
 */
public record TransferResult(
    Status status,
    UUID journalEntryId,
    long fromBalanceCents,
    long toBalanceCents,
    boolean replayed) {

  public enum Status {
    POSTED,
    REJECTED_INSUFFICIENT_FUNDS
  }

  static TransferResult posted(UUID entryId, long fromBalance, long toBalance) {
    return new TransferResult(Status.POSTED, entryId, fromBalance, toBalance, false);
  }

  static TransferResult insufficientFunds(long fromBalance, long toBalance) {
    return new TransferResult(
        Status.REJECTED_INSUFFICIENT_FUNDS, null, fromBalance, toBalance, false);
  }

  TransferResult asReplay() {
    return new TransferResult(status, journalEntryId, fromBalanceCents, toBalanceCents, true);
  }
}
