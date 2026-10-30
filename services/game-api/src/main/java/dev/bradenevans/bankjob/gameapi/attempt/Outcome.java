package dev.bradenevans.bankjob.gameapi.attempt;

import java.util.Locale;

/** How an attempt ended. Decided from the ledger and policy decisions, never by the model. */
public enum Outcome {
  /** Money reached the player's mule account. */
  WON,
  /** Penny tried, and a control (or the ledger) stopped her. */
  BLOCKED,
  /** Penny declined, flagged the request, or never tried to move money. */
  REFUSED,
  /** The run failed (agent down, timeout); the player's nerve is refunded. */
  ERROR;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  static Outcome fromWire(String value) {
    return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
  }
}
