package dev.bradenevans.bankjob.gameapi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * Running balance for one account. Hibernate's {@code @Version} turns every update into {@code
 * UPDATE ... WHERE version = ?}, so two transactions that read the same balance cannot both write
 * it: the second fails and is retried with fresh data (no lost updates).
 */
@Entity
@Table(name = "account_balances")
class AccountBalance {

  @Id private UUID accountId;

  @Convert(converter = AccountKindConverter.class)
  @Column(nullable = false, updatable = false)
  private AccountKind kind;

  @Column(nullable = false)
  private long balanceCents;

  // Wrapper type: null marks a new row, so Spring Data persists instead of merging.
  @Version private Long version;

  protected AccountBalance() {}

  AccountBalance(Account account) {
    this.accountId = account.id();
    this.kind = account.kind();
    this.balanceCents = 0;
  }

  UUID accountId() {
    return accountId;
  }

  AccountKind kind() {
    return kind;
  }

  long balanceCents() {
    return balanceCents;
  }

  boolean canApply(long deltaCents) {
    return kind.mayGoNegative() || Math.addExact(balanceCents, deltaCents) >= 0;
  }

  void apply(long deltaCents) {
    if (!canApply(deltaCents)) {
      throw new IllegalStateException("Posting would make " + accountId + " negative");
    }
    balanceCents = Math.addExact(balanceCents, deltaCents);
  }
}
