package dev.bradenevans.bankjob.gameapi.leaderboard;

import dev.bradenevans.bankjob.gameapi.content.GameContent;
import dev.bradenevans.bankjob.gameapi.events.EventEnvelope;
import dev.bradenevans.bankjob.gameapi.events.EventEnvelope.MalformedEventException;
import dev.bradenevans.bankjob.gameapi.events.EventType;
import dev.bradenevans.bankjob.gameapi.events.ProcessedEvents;
import dev.bradenevans.bankjob.gameapi.events.Topics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leaderboard projection from AttemptResolved events. Score (docs/security-and-levels.md): dollars
 * stolen times the level's multiplier, minus one point per attempt used. Failed runs (outcome
 * {@code error}) refund nerve, so they do not count as attempts.
 */
@Component
class LeaderboardProjector {

  static final String CONSUMER = "leaderboard";

  private final ProcessedEvents processed;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final GameContent content;
  private final JsonMapper json;

  LeaderboardProjector(
      ProcessedEvents processed,
      JdbcClient jdbc,
      TransactionTemplate transactions,
      GameContent content,
      JsonMapper json) {
    this.processed = processed;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.content = content;
    this.json = json;
  }

  @KafkaListener(id = CONSUMER, groupId = CONSUMER, topics = Topics.OUTCOMES)
  void onOutcome(ConsumerRecord<String, String> record) {
    EventEnvelope event = EventEnvelope.parse(json, record.value());
    if (event.type() != EventType.ATTEMPT_RESOLVED) {
      throw new MalformedEventException("Unexpected " + event.type().wire() + " on outcomes");
    }
    String outcome = event.text("outcome");
    int level = (int) event.number("level");
    int multiplier;
    try {
      multiplier = content.level(level).multiplier();
    } catch (IllegalArgumentException e) {
      throw new MalformedEventException("AttemptResolved.level is not a level");
    }
    long stolen = "won".equals(outcome) ? event.number("amount_cents") : 0;
    long scoreDelta = stolen / 100 * multiplier - 1;
    UUID session = event.uuid("session_id");
    transactions.executeWithoutResult(
        status -> {
          if (!processed.claim(CONSUMER, event.eventId()) || "error".equals(outcome)) {
            return;
          }
          jdbc.sql(
                  """
                  INSERT INTO leaderboard (session_id, display_alias, score, best_level,
                                           attempts_used, stolen_cents)
                  VALUES (:session, :alias, :score, :best, 1, :stolen)
                  ON CONFLICT (session_id) DO UPDATE SET
                    score = leaderboard.score + excluded.score,
                    best_level = greatest(leaderboard.best_level, excluded.best_level),
                    attempts_used = leaderboard.attempts_used + 1,
                    stolen_cents = leaderboard.stolen_cents + excluded.stolen_cents,
                    updated_at = now()
                  """)
              .param("session", session)
              .param("alias", alias(session))
              .param("score", scoreDelta)
              .param("best", stolen > 0 ? level : null)
              .param("stolen", stolen)
              .update();
        });
  }

  /** A stable public name that does not reveal the session id (which is also the token subject). */
  static String alias(UUID session) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(session.toString().getBytes(StandardCharsets.UTF_8));
      return "Crew-" + HexFormat.of().formatHex(digest, 0, 3).toUpperCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
