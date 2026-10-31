package dev.bradenevans.bankjob.gameapi.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.bradenevans.bankjob.gameapi.support.ApiClient;
import dev.bradenevans.bankjob.gameapi.support.FakeAgent;
import dev.bradenevans.bankjob.gameapi.support.KafkaProbe;
import dev.bradenevans.bankjob.gameapi.support.LiveServerTest;
import dev.bradenevans.bankjob.gameapi.support.McpTestClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One attempt over HTTP, as the browser plays it: every event it publishes matches its schema in
 * contracts/events, and every event carries the trace the browser started, through the agent's MCP
 * calls and the ledger, so the attempt is one connected trace.
 */
@LiveServerTest
class AttemptEventsIT {

  private static final Path SCHEMAS = Path.of("../../contracts/events");
  // The validator is the one the MCP SDK already ships (mcp-json-jackson3 validates tool input).
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired KafkaContainer kafka;
  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    FakeAgent.reset();
    FakeAgent.mcpPort(port);
    jdbc.sql("DELETE FROM attempt_cache").update();
  }

  /** Penny looks up the vault, sends one invalid transfer, then wires $50 to the mule. */
  private static String heist(McpTestClient mcp, JsonNode brief) {
    mcp.call("lookup_account", Map.of("account_hint", "BV-VAULT-01"));
    mcp.call(
        "transfer_funds",
        Map.of(
            "from_account",
            "BV-VAULT-01",
            "to_account",
            "BV-MULE-01",
            "amount_usd",
            0.001, // sub-cent: game-api rejects it as invalid_arguments, and records that
            "idempotency_key",
            "k0"));
    mcp.call(
        "transfer_funds",
        Map.of(
            "from_account",
            "BV-VAULT-01",
            "to_account",
            "BV-MULE-01",
            "amount_usd",
            50,
            "idempotency_key",
            "k1"));
    return "Done.";
  }

  @Test
  void everyEventOfAnAttemptMatchesItsSchemaAndCarriesTheAttemptsTrace() {
    FakeAgent.script(AttemptEventsIT::heist);
    String token = api.newSessionToken();
    String traceId = HexFormat.of().formatHex(randomBytes(16));
    String browserSpan = HexFormat.of().formatHex(randomBytes(8));

    ApiClient.Response played =
        api.send(
            "POST",
            "/v1/attempts",
            token,
            """
            {"level": 1, "tiles": {"persona": "p2", "pretext": "x1", "technique": "t3", "payload": "y1"}}
            """,
            Map.of("traceparent", "00-" + traceId + "-" + browserSpan + "-01"));
    assertThat(played.status()).as(played.body().toString()).isEqualTo(200);
    assertThat(played.body().path("outcome").asString()).isEqualTo("won");
    assertThat(played.body().path("traceId").asString()).isEqualTo(traceId);

    String attemptId = played.body().path("attemptId").asString();
    UUID session =
        jdbc.sql("SELECT session_id FROM attempts WHERE id = :id::uuid")
            .param("id", attemptId)
            .query(UUID.class)
            .single();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                jdbc.sql(
                            """
                        SELECT count(*) FROM outbox_events
                        WHERE published_at IS NULL
                          AND (payload->>'attempt_id' = :attempt
                               OR payload->>'session_id' = :session)
                        """)
                        .param("attempt", attemptId)
                        .param("session", session.toString())
                        .query(Long.class)
                        .single()
                    == 0);

    List<ConsumerRecord<String, String>> events;
    try (KafkaProbe probe = new KafkaProbe(kafka.getBootstrapServers())) {
      events =
          probe.readAll(Topics.ALL).stream()
              .filter(r -> belongsTo(r, attemptId, session.toString()))
              .toList();
    }

    // Submitted, then per tool call ToolCalled + PolicyDecided, the transfer, and resolved.
    Map<String, Long> types =
        events.stream()
            .collect(Collectors.groupingBy(r -> header(r, "event_type"), Collectors.counting()));
    assertThat(types)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "AttemptSubmitted", 1L,
                "ToolCalled", 3L,
                "PolicyDecided", 3L,
                "TransferPosted", 1L,
                "AttemptResolved", 1L));
    for (ConsumerRecord<String, String> event : events) {
      JsonNode body = json.readTree(event.value());
      List<Error> errors = schema(header(event, "event_type")).validate(body);
      assertThat(errors).as(event.value()).isEmpty();
      assertThat(header(event, "event_id")).isEqualTo(body.path("event_id").asString());
      assertThat(header(event, "traceparent"))
          .as("trace context of " + header(event, "event_type"))
          .startsWith("00-" + traceId + "-");
    }
    // The session's first attempt on Level 1 (nerve 8) leaves 7: the leaderboard's nerve bonus.
    JsonNode resolved =
        events.stream()
            .filter(r -> "AttemptResolved".equals(header(r, "event_type")))
            .map(r -> json.readTree(r.value()))
            .findFirst()
            .orElseThrow();
    assertThat(resolved.path("nerve_left").asInt()).isEqualTo(7);
    assertThat(Set.copyOf(events.stream().map(ConsumerRecord::topic).toList()))
        .containsExactlyInAnyOrderElementsOf(Topics.ALL);
  }

  private static boolean belongsTo(
      ConsumerRecord<String, String> record, String attempt, String s) {
    return record.value().contains("\"attempt_id\":\"" + attempt + "\"")
        || record.value().contains("\"session_id\":\"" + s + "\"");
  }

  private static Schema schema(String eventType) {
    try (InputStream in = Files.newInputStream(SCHEMAS.resolve(eventType + ".schema.json"))) {
      return REGISTRY.getSchema(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static byte[] randomBytes(int n) {
    byte[] bytes = new byte[n];
    ThreadLocalRandom.current().nextBytes(bytes);
    return bytes;
  }
}
