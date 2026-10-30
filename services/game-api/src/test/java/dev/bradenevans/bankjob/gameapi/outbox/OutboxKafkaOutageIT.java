package dev.bradenevans.bankjob.gameapi.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptRecord;
import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.attempt.Outcome;
import dev.bradenevans.bankjob.gameapi.branch.BranchDirectory;
import dev.bradenevans.bankjob.gameapi.events.Topics;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailReviewer;
import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import dev.bradenevans.bankjob.gameapi.support.KafkaProbe;
import dev.bradenevans.bankjob.gameapi.support.TestBranches;
import dev.bradenevans.bankjob.gameapi.tools.BankTools;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Failure injection (M3): the broker becomes unreachable while attempts are moving money, then
 * comes back. Every event must reach every consumer exactly once in effect: none lost while the
 * broker was away, and none applied twice when the relay sends them again.
 *
 * <p>"Unreachable" is {@code docker pause}: the broker stops answering mid-request, like a network
 * partition, and keeps its port, so clients reconnect to the same address afterwards.
 */
@IntegrationTest
class OutboxKafkaOutageIT {

  private static final String VAULT = "BV-VAULT-01";
  private static final String MULE = "BV-MULE-01";
  private static final int ATTEMPTS = 20;
  private static final int PAUSE_AFTER = 5;
  private static final long CENTS_PER_ATTEMPT = 100;
  private static final GuardrailReviewer NO_GUARDRAIL =
      (id, request) -> {
        throw new AssertionError("Level 1 has no guardrail");
      };
  private static final Map<String, List<String>> CONSUMERS =
      Map.of(
          "anomaly-scoring", List.of(Topics.LEDGER),
          "leaderboard", List.of(Topics.OUTCOMES),
          "audit", Topics.ALL);

  @Autowired TestBranches branches;
  @Autowired BankTools tools;
  @Autowired AttemptStore attempts;
  @Autowired BranchDirectory directory;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaContainer kafka;

  @Test
  void noEventIsLostOrAppliedTwiceWhenTheBrokerGoesAwayMidTransfer() throws Exception {
    UUID session = branches.newSession();
    CountDownLatch firstFew = new CountDownLatch(PAUSE_AFTER);
    try (KafkaProbe probe = new KafkaProbe(kafka.getBootstrapServers())) {
      // Attempts keep running on another thread while the broker goes away under them.
      CompletableFuture<Void> playing =
          CompletableFuture.runAsync(
              () -> {
                for (int i = 0; i < ATTEMPTS; i++) {
                  playHeist(session, i);
                  firstFew.countDown();
                }
              });
      firstFew.await();
      pauseBroker();
      try {
        playing.get(); // the ledger never waits for Kafka: every transfer commits
        assertThat(muleBalance(session)).isEqualTo(ATTEMPTS * CENTS_PER_ATTEMPT);
        assertThat(unpublished(session)).as("events wait in the outbox").isPositive();
      } finally {
        unpauseBroker();
      }

      awaitAllPublished(session);
      probe.awaitCaughtUp(CONSUMERS, Duration.ofSeconds(90));
      assertEveryEventAppliedOnce(session);

      // The relay crashes after the broker acked but before it marked the rows: on restart it
      // sends all of them again. Consumers must drop every duplicate.
      jdbc.sql("UPDATE outbox_events SET published_at = NULL WHERE id IN (" + EVENTS + ")")
          .param("session", session.toString())
          .update();
      awaitAllPublished(session);
      probe.awaitCaughtUp(CONSUMERS, Duration.ofSeconds(60));
      assertEveryEventAppliedOnce(session);

      Set<String> ids = eventIds(session);
      Map<String, Long> deliveries =
          probe.readAll(Topics.ALL).stream()
              .map(r -> header(r, "event_id"))
              .filter(ids::contains)
              .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
      assertThat(deliveries).hasSize(ids.size());
      assertThat(deliveries.values()).as("each event is on its topic twice").allMatch(n -> n >= 2);
    }
  }

  @Test
  void aMessageNoRetryCanFixGoesToTheDeadLetterTopicAndTheConsumerMovesOn() {
    UUID session = branches.newSession();
    String muleKey = directory.holder(session, MULE).orElseThrow().accountId().toString();
    try (KafkaProbe probe = new KafkaProbe(kafka.getBootstrapServers())) {
      // Same key as the mule's real ledger events, so the same partition: it must not block them.
      String garbage = "not json " + UUID.randomUUID();
      String future =
          """
          {"event_id": "%s", "event_type": "TransferPosted", "schema_version": 99,
           "occurred_at": "2026-10-29T00:00:00Z"}
          """
              .formatted(UUID.randomUUID());
      probe.send(Topics.LEDGER, muleKey, garbage);
      probe.send(Topics.LEDGER, muleKey, future);
      playHeist(session, 0);

      awaitAllPublished(session);
      probe.awaitCaughtUp(CONSUMERS, Duration.ofSeconds(60));
      assertEveryEventAppliedOnce(session, 1);

      List<ConsumerRecord<String, String>> dead =
          probe.readAll(List.of(Topics.deadLetter(Topics.LEDGER))).stream()
              .filter(r -> r.value().equals(garbage) || r.value().equals(future))
              .toList();
      // Both ledger consumers (anomaly scoring and audit) gave up on both messages.
      assertThat(dead).hasSize(4);
      assertThat(dead)
          .allSatisfy(
              r ->
                  assertThat(header(r, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                      .endsWith("MalformedEventException"));
    }
  }

  /** One attempt that wires $1 from the vault to the mule (Level 1 has no controls). */
  private void playHeist(UUID session, int n) {
    AttemptRecord attempt = branches.newAttempt(session, 1, "p1.x1.t3.y1");
    var transfer =
        tools.call(
            attempt.id(),
            "transfer_funds",
            Map.of(
                "from_account",
                VAULT,
                "to_account",
                MULE,
                "amount_usd",
                CENTS_PER_ATTEMPT / 100.0,
                "idempotency_key",
                "k" + n),
            NO_GUARDRAIL);
    assertThat(transfer.isError()).as(transfer.body().toString()).isFalse();
    attempts.finish(
        attempt.ref(),
        new AttemptStore.Finish(Outcome.WON, CENTS_PER_ATTEMPT, "done", "end_turn", 0, 0, false));
  }

  /** The session's outbox rows: its attempts' events plus its ledger events. */
  private static final String EVENTS =
      """
      SELECT id FROM outbox_events
      WHERE payload->>'session_id' = :session
         OR payload->>'attempt_id' IN (SELECT id::text FROM attempts
                                       WHERE session_id = :session::uuid)
      """;

  private Set<String> eventIds(UUID session) {
    return jdbc.sql(EVENTS).param("session", session.toString()).query(String.class).set();
  }

  private long unpublished(UUID session) {
    return jdbc.sql(
            "SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND id IN ("
                + EVENTS
                + ")")
        .param("session", session.toString())
        .query(Long.class)
        .single();
  }

  private void awaitAllPublished(UUID session) {
    await().atMost(Duration.ofSeconds(90)).until(() -> unpublished(session) == 0);
  }

  private void assertEveryEventAppliedOnce(UUID session) {
    assertEveryEventAppliedOnce(session, ATTEMPTS);
  }

  private void assertEveryEventAppliedOnce(UUID session, int heists) {
    Set<String> ids = eventIds(session);
    // Five events per heist: submitted, tool called, policy decided, transfer posted, resolved.
    assertThat(ids).hasSize(5 * heists);
    assertThat(processed("audit", session)).isEqualTo(ids.size());
    assertThat(processed("anomaly-scoring", session)).isEqualTo(heists);
    assertThat(processed("leaderboard", session)).isEqualTo(heists);
    assertThat(
            jdbc.sql("SELECT count(*) FROM audit_log WHERE event_id IN (" + EVENTS + ")")
                .param("session", session.toString())
                .query(Long.class)
                .single())
        .isEqualTo(ids.size());

    UUID mule = directory.holder(session, MULE).orElseThrow().accountId();
    Map<String, Object> signal =
        jdbc.sql(
                "SELECT teller_credits, teller_credit_cents FROM account_signals"
                    + " WHERE account_id = :mule")
            .param("mule", mule)
            .query()
            .singleRow();
    assertThat(signal.get("teller_credits")).isEqualTo(heists);
    assertThat(signal.get("teller_credit_cents")).isEqualTo(heists * CENTS_PER_ATTEMPT);
    assertThat(muleBalance(session)).isEqualTo(heists * CENTS_PER_ATTEMPT);

    Map<String, Object> board =
        jdbc.sql(
                "SELECT attempts_used, stolen_cents, score, levels_cracked FROM leaderboard"
                    + " WHERE session_id = :session")
            .param("session", session)
            .query()
            .singleRow();
    assertThat(board.get("attempts_used")).isEqualTo(heists);
    assertThat(board.get("stolen_cents")).isEqualTo(heists * CENTS_PER_ATTEMPT);
    // Level 1 cracked once, however many heists: its 100 points plus 10 for each of the 8 nerve
    // left (these attempts bypass the nerve budget). Repeat wins only add to the haul.
    assertThat(((Number) board.get("levels_cracked")).intValue()).isEqualTo(1);
    assertThat(board.get("score")).isEqualTo(180L);
  }

  private long processed(String consumer, UUID session) {
    return jdbc.sql(
            "SELECT count(*) FROM processed_events WHERE consumer = :consumer"
                + " AND event_id IN ("
                + EVENTS
                + ")")
        .param("consumer", consumer)
        .param("session", session.toString())
        .query(Long.class)
        .single();
  }

  private long muleBalance(UUID session) {
    return branches.accounts(session).get(MULE).balanceCents();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private void pauseBroker() {
    DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
  }

  private void unpauseBroker() {
    DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
  }
}
