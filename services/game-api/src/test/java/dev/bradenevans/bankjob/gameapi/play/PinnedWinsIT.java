package dev.bradenevans.bankjob.gameapi.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.bradenevans.bankjob.gameapi.support.ApiClient;
import dev.bradenevans.bankjob.gameapi.support.FakeAgent;
import dev.bradenevans.bankjob.gameapi.support.LiveServerTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every winning decision the eval matrix has found (services/evals/pins.json), replayed through
 * today's policy engine and ledger. A pin is what the model decided, so this needs no model: the
 * decision goes into the response cache and the attempt replays it (ADR-0012), with a fresh policy
 * decision for every call. A policy change that closes an intended gap, or reopens a hole that was
 * closed on purpose, fails here before it ships.
 */
@LiveServerTest
@TestPropertySource(properties = "bankjob.game.daily-seed-override=" + PinnedWinsIT.SEED)
class PinnedWinsIT {

  /** The eval matrix pins wins on this seed: security answers and the hidden note follow it. */
  static final String SEED = "evals";

  private static final Path PINS = Path.of("../evals/pins.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ResponseCache cache;
  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    FakeAgent.reset();
    FakeAgent.mcpPort(port);
  }

  @TestFactory
  Stream<DynamicTest> everyPinnedWinReplaysToItsExpectedOutcome() throws Exception {
    JsonNode file = JSON.readTree(Files.readString(PINS));
    assertThat(file.path("dailySeed").asString()).isEqualTo(SEED);
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode pin : file.path("pins")) {
      String name =
          "level %d %s %s (pin %s)"
              .formatted(
                  pin.path("level").asInt(),
                  pin.path("setup").asString(),
                  pin.path("comboIds").get(0).asString(),
                  pin.path("id").asString());
      tests.add(DynamicTest.dynamicTest(name, () -> replay(pin)));
    }
    assertThat(tests).as("pins in %s", PINS).isNotEmpty();
    return tests.stream();
  }

  private void replay(JsonNode pin) {
    jdbc.sql("DELETE FROM attempt_cache").update();
    String token = api.newSessionToken();

    if (!pin.path("setupTranscript").isNull()) {
      String setupCombo = pin.path("setupComboId").asString();
      cache.store(4, SEED, setupCombo, transcript(pin.path("setupTranscript")));
      assertThat(api.attempt(token, body(4, setupCombo)).status()).isEqualTo(200);
      // Level 5's velocity rule reads a Kafka-fed signal; let it settle (ADR-0013).
      await().atMost(Duration.ofSeconds(20)).until(this::anomalyScoringCaughtUp);
    }

    int level = pin.path("level").asInt();
    String combo = pin.path("comboIds").get(0).asString();
    cache.store(level, SEED, combo, transcript(pin.path("transcript")));
    ApiClient.Response response = api.attempt(token, body(level, combo));

    assertThat(response.status()).isEqualTo(200);
    JsonNode result = response.body();
    assertThat(result.path("cached").asBoolean()).isTrue();
    assertThat(result.path("outcome").asString())
        .as(
            "outcome of pin %s; tool calls: %s",
            pin.path("id").asString(), result.path("toolCalls"))
        .isEqualTo(pin.path("expected").asString());
  }

  private boolean anomalyScoringCaughtUp() {
    return jdbc.sql(
                """
                SELECT count(*) FROM outbox_events o
                WHERE o.topic = 'bankjob.ledger' AND NOT EXISTS (
                  SELECT 1 FROM processed_events p
                  WHERE p.consumer = 'anomaly-scoring' AND p.event_id = o.id)
                """)
            .query(Long.class)
            .single()
        == 0;
  }

  private static Transcript transcript(JsonNode pinned) {
    List<Transcript.Call> calls = new ArrayList<>();
    for (JsonNode call : pinned.path("calls")) {
      calls.add(JSON.treeToValue(call, Transcript.Call.class));
    }
    return new Transcript(calls, pinned.path("reply").asString(""), "pinned");
  }

  private static String body(int level, String comboId) {
    String[] t = comboId.split("\\.");
    return """
        {"level": %d, "tiles": {"persona": "%s", "pretext": "%s", "technique": "%s", "payload": "%s"}}
        """
        .formatted(level, t[0], t[1], t[2], t[3]);
  }
}
