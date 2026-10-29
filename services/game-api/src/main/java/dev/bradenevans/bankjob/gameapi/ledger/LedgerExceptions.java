package dev.bradenevans.bankjob.gameapi.ledger;

import java.util.UUID;

/** Exceptions raised by the ledger's public API. */
public final class LedgerExceptions {

  private LedgerExceptions() {}

  public static class UnknownAccountException extends RuntimeException {
    public UnknownAccountException(UUID accountId) {
      super("Unknown account " + accountId);
    }
  }

  /** Both accounts of a transfer must belong to the caller's session. */
  public static class CrossSessionTransferException extends RuntimeException {
    public CrossSessionTransferException() {
      super("Accounts belong to a different session");
    }
  }

  /** The same idempotency key was reused for a different request. */
  public static class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
      super("Idempotency key " + key + " was already used for a different request");
    }
  }

  /** A posting (not a transfer) would take a non-equity account below zero. */
  public static class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId) {
      super("Insufficient funds in account " + accountId);
    }
  }
}
