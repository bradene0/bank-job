package dev.bradenevans.bankjob.gameapi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Immutable
@Table(name = "journal_entries")
class JournalEntry {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  private UUID attemptId;

  private Instant attemptCreatedAt;

  @Column(nullable = false)
  private String description;

  protected JournalEntry() {}

  JournalEntry(String description, AttemptRef attempt) {
    this.description = description;
    if (attempt != null) {
      this.attemptId = attempt.id();
      this.attemptCreatedAt = attempt.createdAt();
    }
  }

  UUID id() {
    return id;
  }
}
