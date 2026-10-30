package dev.bradenevans.bankjob.gameapi.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sessions")
class Session {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(nullable = false, updatable = false)
  private String ipHash;

  @Column(nullable = false, updatable = false)
  private String dailySeed;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private Map<String, Integer> nerveRemaining;

  protected Session() {}

  Session(
      Instant createdAt,
      Instant expiresAt,
      String ipHash,
      String dailySeed,
      Map<String, Integer> nerve) {
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.ipHash = ipHash;
    this.dailySeed = dailySeed;
    this.nerveRemaining = new LinkedHashMap<>(nerve); // keep level order 1..5 in responses
  }

  UUID id() {
    return id;
  }

  Instant createdAt() {
    return createdAt;
  }

  Instant expiresAt() {
    return expiresAt;
  }

  String dailySeed() {
    return dailySeed;
  }

  Map<String, Integer> nerveRemaining() {
    return nerveRemaining;
  }
}
