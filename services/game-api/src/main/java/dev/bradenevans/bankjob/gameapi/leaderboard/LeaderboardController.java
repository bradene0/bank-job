package dev.bradenevans.bankjob.gameapi.leaderboard;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/leaderboard}: the top crews, read from the Kafka-fed projection. Public; entries
 * show an alias, never a session id. Only crews with at least one win are ranked: by score, then by
 * haul (the dollars that reached the mule).
 */
@RestController
class LeaderboardController {

  static final int SIZE = 20;

  record Entry(
      int rank, String alias, long score, int levelsCracked, int bestLevel, long stolenCents) {}

  record Leaderboard(List<Entry> entries) {}

  private final JdbcClient jdbc;

  LeaderboardController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/v1/leaderboard")
  @Transactional(readOnly = true)
  Leaderboard top() {
    List<Entry> entries =
        jdbc.sql(
                """
                SELECT rank() OVER (ORDER BY score DESC, stolen_cents DESC) AS rank,
                       display_alias, score, levels_cracked, best_level, stolen_cents
                FROM leaderboard
                WHERE best_level IS NOT NULL
                ORDER BY score DESC, stolen_cents DESC, updated_at
                LIMIT :size
                """)
            .param("size", SIZE)
            .query(
                (rs, row) ->
                    new Entry(
                        rs.getInt("rank"),
                        rs.getString("display_alias"),
                        rs.getLong("score"),
                        rs.getInt("levels_cracked"),
                        rs.getInt("best_level"),
                        rs.getLong("stolen_cents")))
            .list();
    return new Leaderboard(entries);
  }
}
