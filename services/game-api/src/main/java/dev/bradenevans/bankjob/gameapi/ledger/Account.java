package dev.bradenevans.bankjob.gameapi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "accounts")
class Account {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID sessionId;

  @Column(updatable = false)
  private UUID customerId;

  @Column(nullable = false, updatable = false)
  private String number;

  @Convert(converter = AccountKindConverter.class)
  @Column(nullable = false, updatable = false)
  private AccountKind kind;

  @Column(nullable = false, updatable = false)
  private String currency = "USD";

  protected Account() {}

  Account(UUID sessionId, UUID customerId, String number, AccountKind kind) {
    this.sessionId = sessionId;
    this.customerId = customerId;
    this.number = number;
    this.kind = kind;
  }

  UUID id() {
    return id;
  }

  UUID sessionId() {
    return sessionId;
  }

  UUID customerId() {
    return customerId;
  }

  String number() {
    return number;
  }

  AccountKind kind() {
    return kind;
  }

  String currency() {
    return currency;
  }
}
