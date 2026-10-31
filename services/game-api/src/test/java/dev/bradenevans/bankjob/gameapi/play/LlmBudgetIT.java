package dev.bradenevans.bankjob.gameapi.play;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.support.ApiClient;
import dev.bradenevans.bankjob.gameapi.support.FakeAgent;
import dev.bradenevans.bankjob.gameapi.support.LiveServerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The budget switch end to end, in live mode: every run's token usage is counted in Postgres, and
 * once this month's estimated spend reaches the cutoff (80%), new model calls stop. The player gets
 * 503 {@code replay: true} with reason {@code over_budget} (the game then plays the recorded
 * result) and keeps the nerve; cached decisions still play, because they cost nothing.
 *
 * <p>Budget: 1 cent = 10,000 micro-dollars, cutoff at 8,000. Each run reports 30,000 input tokens
 * at $0.10 per million = 3,000 micro-dollars.
 */
@LiveServerTest
@TestPropertySource(
    properties = {
      "bankjob.llm.mode=live",
      "bankjob.llm.monthly-budget-cents=1",
      "bankjob.llm.input-micros-per-mtok=100000"
    })
class LlmBudgetIT {

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    FakeAgent.reset();
    FakeAgent.mcpPort(port);
    FakeAgent.inputTokensPerRun(30_000);
    FakeAgent.model("claude-haiku-5-5"); // live mode caches only the configured model's answers
    jdbc.sql("DELETE FROM attempt_cache").update();
    jdbc.sql("DELETE FROM llm_usage").update();
  }

  @AfterEach
  void tearDown() {
    FakeAgent.reset();
    jdbc.sql("DELETE FROM llm_usage").update();
  }

  private static String body(String persona) {
    return """
        {"level": 1, "tiles": {"persona": "%s", "pretext": "x1", "technique": "t3", "payload": "y1"}}
        """
        .formatted(persona);
  }

  private long spentMicros() {
    return jdbc.sql("SELECT coalesce(sum(est_cost_micros), 0) FROM llm_usage")
        .query(Long.class)
        .single();
  }

  @Test
  void nearTheBudgetNewCallsSwitchToReplayAndCachedOnesStillPlay() {
    String token = api.newSessionToken();

    // 0% -> 30% -> 60% -> 90%: each call starts below the 80% cutoff, so all three run.
    for (String persona : new String[] {"p1", "p2", "p3"}) {
      assertThat(api.attempt(token, body(persona)).status()).isEqualTo(200);
    }
    assertThat(FakeAgent.RUNS.get()).isEqualTo(3);
    assertThat(spentMicros()).isEqualTo(9_000);

    // 90% of the budget: below 100%, but past the cutoff. The model is not called.
    ApiClient.Response over = api.attempt(token, body("p4"));
    assertThat(over.status()).isEqualTo(503);
    assertThat(over.body().path("replay").asBoolean()).isTrue();
    assertThat(over.body().path("reason").asString()).isEqualTo("over_budget");
    assertThat(FakeAgent.RUNS.get()).isEqualTo(3);

    // A con someone already played replays its cached decisions for free, nerve and all.
    JsonNode cached = api.attempt(token, body("p1")).body();
    assertThat(cached.path("cached").asBoolean()).isTrue();
    assertThat(FakeAgent.RUNS.get()).isEqualTo(3);
    // Three runs and one cache hit spent nerve; the over-budget call was refunded.
    assertThat(cached.path("nerveRemaining").path("1").asInt()).isEqualTo(8 - 4);
    assertThat(spentMicros()).isEqualTo(9_000);
  }
}
