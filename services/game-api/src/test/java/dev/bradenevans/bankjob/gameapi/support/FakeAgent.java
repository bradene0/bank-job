package dev.bradenevans.bankjob.gameapi.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-process stand-in for the Python agent that speaks the same contract
 * (docs/contracts/agent-mcp.md): it accepts only a token (and a review id), and gets everything
 * else from game-api's MCP server, exactly like the real agent. Tests script what "Penny" does.
 */
public final class FakeAgent {

  /** What Penny does with her MCP connection and brief; returns her reply. */
  @FunctionalInterface
  public interface Script {
    String run(McpTestClient mcp, JsonNode brief);
  }

  /** The guardrail's verdict on a pending review: "allow" or "deny". */
  @FunctionalInterface
  public interface Reviewer {
    String review(JsonNode review);
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpServer SERVER = start();

  public static final AtomicInteger RUNS = new AtomicInteger();
  public static final AtomicInteger REVIEWS = new AtomicInteger();
  private static volatile Script script = (mcp, brief) -> "Hello from the fake agent.";
  private static volatile Reviewer reviewer = review -> "deny";
  private static volatile boolean down;
  private static volatile int mcpPort;

  private FakeAgent() {}

  public static void reset() {
    RUNS.set(0);
    REVIEWS.set(0);
    script = (mcp, brief) -> "Hello from the fake agent.";
    reviewer = review -> "deny";
    down = false;
  }

  public static void script(Script value) {
    script = value;
  }

  public static void reviewer(Reviewer value) {
    reviewer = value;
  }

  public static void down(boolean value) {
    down = value;
  }

  private static HttpServer start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // reviews nest in runs
      server.createContext("/v1/runs", FakeAgent::run);
      server.createContext("/v1/reviews", FakeAgent::review);
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void run(HttpExchange exchange) throws IOException {
    RUNS.incrementAndGet();
    if (down) {
      respond(exchange, 502, Map.of("title", "down", "status", 502));
      return;
    }
    JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
    String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
    try (McpTestClient mcp =
        McpTestClient.connect(mcpPort, body.path("attemptToken").asString(), traceparent)) {
      JsonNode brief = mcp.brief();
      String reply = script.run(mcp, brief);
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("reply", reply);
      response.put("stopReason", "stub");
      response.put("model", "stub");
      response.put("toolCalls", 0);
      response.put("usage", Map.of("inputTokens", 0, "outputTokens", 0));
      respond(exchange, 200, response);
    } catch (RuntimeException e) {
      respond(exchange, 502, Map.of("title", e.getClass().getSimpleName(), "status", 502));
    }
  }

  private static void review(HttpExchange exchange) throws IOException {
    REVIEWS.incrementAndGet();
    JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
    try (McpTestClient mcp = McpTestClient.connect(mcpPort, body.path("attemptToken").asString())) {
      JsonNode review = mcp.read("bankjob://reviews/" + body.path("reviewId").asString());
      respond(
          exchange,
          200,
          Map.of(
              "verdict",
              reviewer.review(review),
              "reason",
              "fake guardrail",
              "model",
              "stub",
              "usage",
              Map.of("inputTokens", 0, "outputTokens", 0)));
    } catch (RuntimeException e) {
      respond(exchange, 502, Map.of("title", e.getClass().getSimpleName(), "status", 502));
    }
  }

  private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /** Points game-api at the fake. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    DynamicPropertyRegistrar fakeAgentUrl() {
      return registry ->
          registry.add(
              "bankjob.llm.agent.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }
  }

  /** Where the fake connects back to game-api's MCP server; call from @BeforeEach. */
  public static void mcpPort(int port) {
    mcpPort = port;
  }
}
