package dev.bradenevans.bankjob.gameapi.attempt;

import dev.bradenevans.bankjob.gameapi.ledger.AttemptRef;
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

/**
 * Attempts and their tool calls. Plain SQL rather than JPA: the attempts table is partitioned with
 * a composite key (ADR-0010), and these rows are written once and read as a timeline.
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

  AttemptStore(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * @param traceId the trace this attempt runs in, so its owner can fetch it later; null if the
   *     request is not traced
   */
  @Transactional
  public AttemptRecord create(
      UUID sessionId, int level, String comboId, JsonNode tiles, String dailySeed, String traceId) {
    return jdbc.sql(
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
    return jdbc.sql(
                """
                UPDATE attempts
                SET outcome = :outcome, amount_cents = :amount, reply = :reply,
                    stop_reason = :stop, input_tokens = :input, output_tokens = :output,
                    cached = :cached, finished_at = now()
                WHERE id = :id AND created_at = :created AND outcome IS NULL
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
            .update()
        == 1;
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
