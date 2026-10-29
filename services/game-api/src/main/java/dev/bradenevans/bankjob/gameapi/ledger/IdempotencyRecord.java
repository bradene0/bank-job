package dev.bradenevans.bankjob.gameapi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "idempotency_keys")
class IdempotencyRecord {

  @Id private String key;

  @Column(nullable = false)
  private String requestHash;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String responseJson;

  protected IdempotencyRecord() {}

  IdempotencyRecord(String key, String requestHash, String responseJson) {
    this.key = key;
    this.requestHash = requestHash;
    this.responseJson = responseJson;
  }

  String requestHash() {
    return requestHash;
  }

  String responseJson() {
    return responseJson;
  }
}
