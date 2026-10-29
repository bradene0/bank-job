package dev.bradenevans.bankjob.gameapi.branch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "customers")
class Customer {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID sessionId;

  @Column(nullable = false)
  private String displayName;

  @Column(nullable = false)
  private String securityQuestion;

  @Column(nullable = false)
  private String securityAnswerHash;

  @Column(nullable = false)
  private String phone;

  protected Customer() {}

  Customer(UUID sessionId, BranchSeed.SeedCustomer seed, String answerHash) {
    this.sessionId = sessionId;
    this.displayName = seed.displayName();
    this.securityQuestion = seed.securityQuestion();
    this.securityAnswerHash = answerHash;
    this.phone = seed.phone();
  }

  UUID id() {
    return id;
  }

  String displayName() {
    return displayName;
  }
}
