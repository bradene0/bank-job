package dev.bradenevans.bankjob.gameapi.play;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the agent service (docs/contracts/agent-mcp.md). Requests carry only the attempt token and
 * ids, never text: the agent reads everything it needs from game-api's MCP server.
 */
@Component
@RegisterReflectionForBinding({
  AgentClient.RunResult.class,
  AgentClient.ReviewResult.class,
  AgentClient.Usage.class
})
class AgentClient {

  record Usage(int inputTokens, int outputTokens) {
    static final Usage NONE = new Usage(0, 0);
  }

  record RunResult(String reply, String stopReason, String model, int toolCalls, Usage usage) {}

  record ReviewResult(String verdict, String reason, String model, Usage usage) {}

  /** The agent could not be reached, timed out, or failed. The player falls back to replay. */
  static class AgentUnavailableException extends RuntimeException {
    AgentUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final RestClient runs;
  private final RestClient reviews;

  AgentClient(LlmProperties properties) {
    LlmProperties.Agent agent = properties.agent();
    this.runs = client(agent, agent.runTimeout());
    this.reviews = client(agent, agent.reviewTimeout());
  }

  private static RestClient client(LlmProperties.Agent agent, Duration readTimeout) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(agent.connectTimeout()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(agent.url()).requestFactory(factory).build();
  }

  RunResult run(String attemptToken) {
    try {
      return runs.post()
          .uri("/v1/runs")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("attemptToken", attemptToken))
          .retrieve()
          .body(RunResult.class);
    } catch (RestClientException e) {
      throw new AgentUnavailableException("Agent run failed: " + e.getClass().getSimpleName(), e);
    }
  }

  ReviewResult review(String attemptToken, UUID reviewId) {
    try {
      return reviews
          .post()
          .uri("/v1/reviews")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("attemptToken", attemptToken, "reviewId", reviewId.toString()))
          .retrieve()
          .body(ReviewResult.class);
    } catch (RestClientException e) {
      throw new AgentUnavailableException(
          "Guardrail review failed: " + e.getClass().getSimpleName(), e);
    }
  }
}
