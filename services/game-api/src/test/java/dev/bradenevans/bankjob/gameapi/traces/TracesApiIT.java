package dev.bradenevans.bankjob.gameapi.traces;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.support.ApiClient;
import dev.bradenevans.bankjob.gameapi.support.FakeAgent;
import dev.bradenevans.bankjob.gameapi.support.LiveServerTest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Trace ids over HTTP: game-api continues the browser's trace, records it on the attempt, and shows
 * it only to the session that made it.
 */
@LiveServerTest
class TracesApiIT {

  private static final String ATTEMPT =
      """
      {"level": 1, "tiles": {"persona": "p2", "pretext": "x1", "technique": "t3", "payload": "y1"}}
      """;

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    FakeAgent.reset();
    FakeAgent.mcpPort(port);
    jdbc.sql("DELETE FROM attempt_cache").update();
  }

  @Test
  void anAttemptContinuesTheBrowsersTrace() {
    String traceId = hex(16);
    ApiClient.Response played =
        api.send(
            "POST",
            "/v1/attempts",
            api.newSessionToken(),
            ATTEMPT,
            Map.of("traceparent", "00-" + traceId + "-" + hex(8) + "-01"));

    assertThat(played.status()).isEqualTo(200);
    assertThat(played.body().path("traceId").asString()).isEqualTo(traceId);
    assertThat(
            jdbc.sql("SELECT trace_id FROM attempts WHERE id = :id::uuid")
                .param("id", played.body().path("attemptId").asString())
                .query(String.class)
                .single())
        .isEqualTo(traceId);
  }

  @Test
  void aTraceIsOnlyVisibleToTheSessionThatMadeIt() {
    String owner = api.newSessionToken();
    String traceId = api.attempt(owner, ATTEMPT).body().path("traceId").asString();
    assertThat(traceId).matches("[0-9a-f]{32}");

    assertThat(api.get(api.newSessionToken(), "/v1/traces/" + traceId).status()).isEqualTo(404);
    assertThat(api.get(owner, "/v1/traces/" + traceId.toUpperCase()).status()).isEqualTo(404);
    assertThat(api.get(owner, "/v1/traces/not-a-trace").status()).isEqualTo(404);
    assertThat(api.get(null, "/v1/traces/" + traceId).status()).isEqualTo(401);
    // The owner gets through to the trace store, which tests point at a closed port.
    ApiClient.Response unavailable = api.get(owner, "/v1/traces/" + traceId);
    assertThat(unavailable.status()).isEqualTo(503);
  }

  private static String hex(int bytes) {
    byte[] random = new byte[bytes];
    ThreadLocalRandom.current().nextBytes(random);
    return HexFormat.of().formatHex(random);
  }
}
