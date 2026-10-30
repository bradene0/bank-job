package dev.bradenevans.bankjob.gameapi.attempt;

import dev.bradenevans.bankjob.gameapi.events.EventType;
import dev.bradenevans.bankjob.gameapi.ledger.AttemptRef;
import dev.bradenevans.bankjob.gameapi.outbox.Outbox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Attempts and their tool calls. Plain SQL rather than JPA: the attempts table is partitioned with
 * a composite key (ADR-0010), and these rows are written once and read as a timeline.
 *
 * <p>Each write appends its event to the outbox in the same transaction (AttemptSubmitted,
 * ToolCalled + PolicyDecided, AttemptResolved).
 */
@Repository
public class AttemptStore {

  /** What the model and the run reported when an attempt finished. */
  public record Finish(
      Outcome outcome,
      long amountCents,
      String reply,
      String stopReason,
      int inputTokens,
      int outputTokens,
      boolean cached) {}

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Outbox outbox;

  AttemptStore(JdbcClient jdbc, JsonMapper json, Outbox outbox) {
    this.jdbc = jdbc;
    this.json = json;
    this.outbox = outbox;
  }

  /**
   * @param traceId the trace this attempt runs in, so its owner can fetch it later; null if the
   *     request is not traced
   */
  @Transactional
  public AttemptRecord create(
      UUID sessionId, int level, String comboId, JsonNode tiles, String dailySeed, String traceId) {
    AttemptRecord attempt =
        jdbc.sql(
                """
                INSERT INTO attempts (session_id, level, combo_id, tiles, daily_seed, trace_id)
                VALUES (:session, :level, :combo, :tiles::jsonb, :seed, :trace)
                RETURNING id, created_at, session_id, level, combo_id, daily_seed, outcome
                """)
            .param("session", sessionId)
            .param("level", level)
            .param("combo", comboId)
            .param("tiles", json.writeValueAsString(tiles))
            .param("seed", dailySeed)
            .param("trace", traceId)
            .query(AttemptStore::attempt)
            .single();
    ObjectNode event = json.createObjectNode();
    event.put("attempt_id", attempt.id().toString());
    event.put("session_id", sessionId.toString());
    event.put("level", level);
    event.put("combo_id", comboId);
    event.set("tiles", tiles);
    event.put("daily_seed", dailySeed);
    outbox.append(
        EventType.ATTEMPT_SUBMITTED,
        "attempt",
        attempt.id().toString(),
        sessionId.toString(),
        event);
    return attempt;
  }

  /** True if this session ran an attempt in the given trace (guards GET /v1/traces/{id}). */
  @Transactional(readOnly = true)
  public boolean sessionOwnsTrace(UUID sessionId, String traceId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM attempts WHERE session_id = :session AND trace_id = :trace)")
        .param("session", sessionId)
        .param("trace", traceId)
        .query(Boolean.class)
        .single();
  }

  @Transactional(readOnly = true)
  public Optional<AttemptRecord> find(UUID attemptId) {
    return jdbc.sql(
            """
            SELECT id, created_at, session_id, level, combo_id, daily_seed, outcome
            FROM attempts WHERE id = :id
            """)
        .param("id", attemptId)
        .query(AttemptStore::attempt)
        .optional();
  }

  /** Sets the outcome once; returns false if the attempt had already finished. */
  @Transactional
  public boolean finish(AttemptRef attempt, Finish finish) {
    Optional<ObjectNode> finished =
        jdbc.sql(
                """
                UPDATE attempts
                SET outcome = :outcome, amount_cents = :amount, reply = :reply,
                    stop_reason = :stop, input_tokens = :input, output_tokens = :output,
                    cached = :cached, finished_at = now()
                WHERE id = :id AND created_at = :created AND outcome IS NULL
                RETURNING session_id, level, combo_id
                """)
            .param("outcome", finish.outcome().wire())
            .param("amount", finish.amountCents())
            .param("reply", finish.reply())
            .param("stop", finish.stopReason())
            .param("input", finish.inputTokens())
            .param("output", finish.outputTokens())
            .param("cached", finish.cached())
            .param("id", attempt.id())
            .param("created", Timestamp.from(attempt.createdAt()))
            .query(
                (rs, row) -> {
                  ObjectNode event = json.createObjectNode();
                  event.put("attempt_id", attempt.id().toString());
                  event.put("session_id", rs.getObject("session_id", UUID.class).toString());
                  event.put("level", rs.getInt("level"));
                  event.put("combo_id", rs.getString("combo_id"));
                  return event;
                })
            .optional();
    finished.ifPresent(
        event -> {
          event.put("outcome", finish.outcome().wire());
          event.put("amount_cents", finish.amountCents());
          event.put("cached", finish.cached());
          outbox.append(
              EventType.ATTEMPT_RESOLVED,
              "attempt",
              attempt.id().toString(),
              event.get("session_id").asString(),
              event);
        });
    return finished.isPresent();
  }

  @Transactional(readOnly = true)
  public List<ToolCallRecord> toolCalls(UUID attemptId) {
    return jdbc.sql(
            """
            SELECT ordinal, tool, args, policy_decision, policy_reason, result, review, latency_ms
            FROM tool_calls WHERE attempt_id = :id ORDER BY ordinal
            """)
        .param("id", attemptId)
        .query(
            (rs, row) ->
                new ToolCallRecord(
                    rs.getInt("ordinal"),
                    rs.getString("tool"),
                    json.readTree(rs.getString("args")),
                    "allow".equals(rs.getString("policy_decision")),
                    rs.getString("policy_reason"),
                    json.readTree(rs.getString("result")),
                    rs.getString("review") == null ? null : json.readTree(rs.getString("review")),
                    rs.getInt("latency_ms")))
        .list();
  }

  @Transactional
  public void recordToolCall(AttemptRef attempt, ToolCallRecord call) {
    jdbc.sql(
            """
            INSERT INTO tool_calls (attempt_id, attempt_created_at, ordinal, tool, args,
                                    policy_decision, policy_reason, result, review, latency_ms)
            VALUES (:id, :created, :ordinal, :tool, :args::jsonb, :decision, :reason,
                    :result::jsonb, :review::jsonb, :latency)
            """)
        .param("id", attempt.id())
        .param("created", Timestamp.from(attempt.createdAt()))
        .param("ordinal", call.ordinal())
        .param("tool", call.tool())
        .param("args", json.writeValueAsString(call.args()))
        .param("decision", call.allowed() ? "allow" : "deny")
        .param("reason", call.policyReason())
        .param("result", json.writeValueAsString(call.result()))
        .param("review", call.review() == null ? null : json.writeValueAsString(call.review()))
        .param("latency", call.latencyMs())
        .update();

    String id = attempt.id().toString();
    ObjectNode called = json.createObjectNode();
    called.put("attempt_id", id);
    called.put("ordinal", call.ordinal());
    called.put("tool", call.tool());
    called.set("args", call.args());
    outbox.append(EventType.TOOL_CALLED, "attempt", id, id, called);

    ObjectNode decided = json.createObjectNode();
    decided.put("attempt_id", id);
    decided.put("ordinal", call.ordinal());
    decided.put("tool", call.tool());
    decided.put("decision", call.allowed() ? "allow" : "deny");
    decided.put("reason_code", reasonCode(call.policyReason()));
    decided.put("reason", call.policyReason());
    decided.set("review", call.review());
    outbox.append(EventType.POLICY_DECIDED, "attempt", id, id, decided);
  }

  /** Policy reasons are "code: explanation"; the code is the stable part. */
  static String reasonCode(String reason) {
    int colon = reason.indexOf(':');
    return colon < 0 ? reason : reason.substring(0, colon);
  }

  private static AttemptRecord attempt(ResultSet rs, int row) throws SQLException {
    return new AttemptRecord(
        rs.getObject("id", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("session_id", UUID.class),
        rs.getInt("level"),
        rs.getString("combo_id"),
        rs.getString("daily_seed"),
        Outcome.fromWire(rs.getString("outcome")));
  }
}
