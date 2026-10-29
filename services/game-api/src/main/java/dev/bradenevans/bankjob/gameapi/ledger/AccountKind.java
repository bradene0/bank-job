package dev.bradenevans.bankjob.gameapi.ledger;

public enum AccountKind {
  CHECKING,
  VAULT,
  MULE,
  /** Per-session opening-balance account; the only kind allowed to go negative. */
  EQUITY;

  boolean mayGoNegative() {
    return this == EQUITY;
  }

  String dbValue() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  static AccountKind fromDbValue(String value) {
    return valueOf(value.toUpperCase(java.util.Locale.ROOT));
  }
}
