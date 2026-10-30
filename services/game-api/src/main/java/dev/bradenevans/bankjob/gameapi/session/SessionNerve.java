package dev.bradenevans.bankjob.gameapi.session;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spends and refunds a session's per-level attempt budget ("nerve"). Each spend is one atomic
 * conditional UPDATE, so concurrent attempts can never take a level below zero.
 */
@Service
public class SessionNerve {

  /** The session's daily seed and its nerve after the spend. */
  public record Spent(String dailySeed, Map<String, Integer> nerveRemaining) {}

  private static final TypeReference<Map<String, Integer>> NERVE = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;

  SessionNerve(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Empty if the session has expired or has no nerve left for the level. */
  @Transactional
  public Optional<Spent> spend(UUID sessionId, int level) {
    return jdbc.sql(
            """
            UPDATE sessions
            SET nerve_remaining = jsonb_set(nerve_remaining, ARRAY[:level],
                                            to_jsonb((nerve_remaining ->> :level)::int - 1))
            WHERE id = :id AND expires_at > now()
              AND coalesce((nerve_remaining ->> :level)::int, 0) > 0
            RETURNING daily_seed, nerve_remaining::text AS nerve
            """)
        .param("id", sessionId)
        .param("level", Integer.toString(level))
        .query(
            (rs, row) ->
                new Spent(rs.getString("daily_seed"), json.readValue(rs.getString("nerve"), NERVE)))
        .optional();
  }

  /** Gives an attempt back when it failed for reasons that were not the player's. */
  @Transactional
  public Map<String, Integer> refund(UUID sessionId, int level) {
    return jdbc.sql(
            """
            UPDATE sessions
            SET nerve_remaining = jsonb_set(nerve_remaining, ARRAY[:level],
                                            to_jsonb((nerve_remaining ->> :level)::int + 1))
            WHERE id = :id
            RETURNING nerve_remaining::text AS nerve
            """)
        .param("id", sessionId)
        .param("level", Integer.toString(level))
        .query((rs, row) -> json.readValue(rs.getString("nerve"), NERVE))
        .single();
  }
}
