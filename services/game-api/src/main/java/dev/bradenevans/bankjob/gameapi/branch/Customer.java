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

  @Column(nullable = false, updatable = false)
  private String displayName;

  @Column(nullable = false, updatable = false)
  private String securityQuestion;

  @Column(nullable = false, updatable = false)
  private String securityAnswerHash;

  @Column(nullable = false, updatable = false)
  private String securityAnswerHint;

  // The only mutable column: update_contact_info changes it (the Level 4 gap).
  @Column(nullable = false)
  private String phone;

  protected Customer() {}

  Customer(
      UUID sessionId,
      BranchSeed.SeedCustomer seed,
      String securityQuestion,
      String answerHash,
      String answerHint) {
    this.sessionId = sessionId;
    this.displayName = seed.displayName();
    this.securityQuestion = securityQuestion;
    this.securityAnswerHash = answerHash;
    this.securityAnswerHint = answerHint;
    this.phone = seed.phone();
  }

  UUID id() {
    return id;
  }

  UUID sessionId() {
    return sessionId;
  }

  String displayName() {
    return displayName;
  }

  String securityQuestion() {
    return securityQuestion;
  }

  String securityAnswerHash() {
    return securityAnswerHash;
  }

  String securityAnswerHint() {
    return securityAnswerHint;
  }

  String phone() {
    return phone;
  }

  void changePhone(String newPhone) {
    this.phone = newPhone;
  }
}
