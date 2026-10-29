package dev.bradenevans.bankjob.gameapi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** One leg of a journal entry. Signed: debit is negative, credit is positive. */
@Entity
@Immutable
@Table(name = "postings")
class Posting {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(nullable = false)
  private UUID entryId;

  @Column(nullable = false)
  private UUID accountId;

  @Column(nullable = false)
  private long amountCents;

  protected Posting() {}

  Posting(UUID entryId, UUID accountId, long amountCents) {
    this.entryId = entryId;
    this.accountId = accountId;
    this.amountCents = amountCents;
  }
}
