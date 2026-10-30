package dev.bradenevans.bankjob.gameapi.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.attempt.Outcome;
import dev.bradenevans.bankjob.gameapi.session.SessionNerve;
import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import dev.bradenevans.bankjob.gameapi.support.TestBranches;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Scoring by level (docs/security-and-levels.md), through the real outbox, Kafka and projection: a
 * level's points and the nerve bonus come once, with the first win on it; repeat wins only add to
 * the haul, which breaks ties.
 */
@IntegrationTest
class LeaderboardScoringIT {

  @Autowired TestBranches branches;
  @Autowired SessionNerve nerve;
  @Autowired AttemptStore attempts;
  @Autowired LeaderboardController leaderboard;
  @Autowired JdbcClient jdbc;

  @Test
  void aLevelScoresOnceWithItsNerveBonusAndRepeatWinsOnlyAddToTheHaul() {
    UUID session = branches.newSession();
    play(session, 1, Outcome.REFUSED, 0); // nerve 8 -> 7: costs nothing
    play(session, 1, Outcome.WON, 20_000); // 7 -> 6: cracks Level 1, 100 + 10 x 6
    play(session, 1, Outcome.WON, 20_000); // 6 -> 5: already cracked, haul only
    // A failed run is not an attempt for the leaderboard. (Not refunded here: nerve 6 -> 5.)
    play(session, 3, Outcome.ERROR, 0);
    play(session, 3, Outcome.BLOCKED, 0); // 5 -> 4: costs nothing
    play(session, 3, Outcome.WON, 20_000); // 4 -> 3: cracks Level 3, 300 + 10 x 3

    Map<String, Object> row = awaitBoard(session, 5);
    assertThat(((Number) row.get("score")).longValue()).isEqualTo(160 + 330);
    assertThat(((Number) row.get("levels_cracked")).intValue()).isEqualTo(2);
    assertThat(((Number) row.get("best_level")).intValue()).isEqualTo(3);
    assertThat(((Number) row.get("stolen_cents")).longValue()).isEqualTo(60_000);
    assertThat(
            jdbc.sql(
                    "SELECT level || ':' || points FROM leaderboard_levels WHERE session_id = :s"
                        + " ORDER BY level")
                .param("s", session)
                .query(String.class)
                .list())
        .containsExactly("1:160", "3:330");
  }

  @Test
  void crewsWithTheSameScoreAreRankedByTheirHaul() {
    UUID smallHaul = crackEveryLevel(20_000);
    UUID bigHaul = crackEveryLevel(500_000);

    List<LeaderboardController.Entry> top = leaderboard.top().entries();
    var small = entry(top, smallHaul);
    var big = entry(top, bigHaul);
    // One attempt per level: 1500 points plus 10 x (7 + 6 + 5 + 4 + 3), the most a crew can score.
    assertThat(small.score()).isEqualTo(1750L);
    assertThat(big.score()).isEqualTo(1750L);
    assertThat(big.levelsCracked()).isEqualTo(5);
    assertThat(big.rank()).isLessThan(small.rank());
  }

  /** One winning attempt on each level, as a first-time crew would play them. */
  private UUID crackEveryLevel(long centsPerWin) {
    UUID session = branches.newSession();
    for (int level = 1; level <= 5; level++) {
      play(session, level, Outcome.WON, centsPerWin);
    }
    awaitBoard(session, 5);
    return session;
  }

  /** Spends nerve like POST /v1/attempts, then finishes the attempt with the given outcome. */
  private void play(UUID session, int level, Outcome outcome, long cents) {
    assertThat(nerve.spend(session, level)).isPresent();
    var attempt = branches.newAttempt(session, level, "p1.x1.t3.y1");
    attempts.finish(
        attempt.ref(), new AttemptStore.Finish(outcome, cents, "x", "end_turn", 0, 0, false));
  }

  private Map<String, Object> awaitBoard(UUID session, int attemptsUsed) {
    return await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                jdbc.sql(
                        "SELECT score, levels_cracked, best_level, stolen_cents, attempts_used"
                            + " FROM leaderboard WHERE session_id = :s")
                    .param("s", session)
                    .query()
                    .listOfRows(),
            rows ->
                rows.size() == 1
                    && ((Number) rows.get(0).get("attempts_used")).intValue() == attemptsUsed)
        .get(0);
  }

  private static LeaderboardController.Entry entry(
      List<LeaderboardController.Entry> top, UUID session) {
    String alias = LeaderboardProjector.alias(session);
    return top.stream()
        .filter(e -> e.alias().equals(alias))
        .findFirst()
        .orElseThrow(() -> new AssertionError(alias + " is not on the leaderboard: " + top));
  }
}
