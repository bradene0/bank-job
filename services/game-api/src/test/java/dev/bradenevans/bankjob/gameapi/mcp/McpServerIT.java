package dev.bradenevans.bankjob.gameapi.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptRecord;
import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.attempt.Outcome;
import dev.bradenevans.bankjob.gameapi.security.AttemptTokenService;
import dev.bradenevans.bankjob.gameapi.support.ApiClient;
import dev.bradenevans.bankjob.gameapi.support.LiveServerTest;
import dev.bradenevans.bankjob.gameapi.support.McpTestClient;
import dev.bradenevans.bankjob.gameapi.support.TestBranches;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** The MCP server over real streamable HTTP, as the agent sees it. */
@LiveServerTest
class McpServerIT {

  @LocalServerPort int port;
  @Autowired TestBranches branches;
  @Autowired AttemptTokenService attemptTokens;
  @Autowired AttemptStore attempts;

  private ApiClient api;
  private AttemptRecord attempt;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    attempt = branches.newAttempt(branches.newSession(), 3, "p3.x1.t4.y1");
  }

  @Test
  void listsSevenStrictTools() {
    try (McpTestClient mcp = McpTestClient.connect(port, attemptTokens.issue(attempt.id()))) {
      List<McpSchema.Tool> tools = mcp.raw().listTools().tools();

      assertThat(tools)
          .extracting(McpSchema.Tool::name)
          .containsExactlyInAnyOrder(
              "lookup_account",
              "get_balance",
              "verify_identity",
              "read_document",
              "update_contact_info",
              "transfer_funds",
              "flag_for_review");
      for (McpSchema.Tool tool : tools) {
        Map<String, Object> schema = tool.inputSchema();
        assertThat(schema.get("additionalProperties")).as(tool.name()).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(new HashSet<>((List<?>) schema.get("required")))
            .as(tool.name())
            .isEqualTo(properties.keySet());
      }
    }
  }

  @Test
  void theBriefCarriesServerAssembledPromptsAndNoTileIdsInTheText() {
    try (McpTestClient mcp = McpTestClient.connect(port, attemptTokens.issue(attempt.id()))) {
      JsonNode brief = mcp.brief();

      assertThat(brief.path("attemptId").asString()).isEqualTo(attempt.id().toString());
      assertThat(brief.path("level").asInt()).isEqualTo(3);
      assertThat(brief.path("comboId").asString()).isEqualTo("p3.x1.t4.y1");
      assertThat(brief.path("system").asString()).contains("You are Penny");
      assertThat(brief.path("message").asString())
          .contains("This is Brassvale's fraud department.")
          .contains("<customer_message>")
          .contains("customer document CD-1")
          .doesNotContain("p3", "t4");
      assertThat(brief.path("stub").path("documentIds").get(0).asString()).isEqualTo("CD-1");
      assertThat(brief.path("stub").path("payloadAmountCents").asLong()).isEqualTo(20_000);
    }
  }

  @Test
  void toolCallsAreAuthorizedAndRecorded() {
    try (McpTestClient mcp = McpTestClient.connect(port, attemptTokens.issue(attempt.id()))) {
      JsonNode lookup = mcp.call("lookup_account", Map.of("account_hint", "BV-VAULT-01"));
      JsonNode transfer =
          mcp.call(
              "transfer_funds",
              Map.of(
                  "from_account", "BV-VAULT-01",
                  "to_account", "BV-MULE-01",
                  "amount_usd", 200,
                  "idempotency_key", "k1"));
      JsonNode document = mcp.call("read_document", Map.of("document_id", "CD-1"));

      assertThat(lookup.path("matches").get(0).path("answerHint").asString()).contains("-");
      assertThat(transfer.path("_isError").asBoolean()).isTrue();
      assertThat(transfer.path("reason").asString()).contains("verify_identity");
      assertThat(document.path("title").asString()).isNotBlank();
    }
    assertThat(attempts.toolCalls(attempt.id()))
        .extracting(c -> c.tool() + "=" + (c.allowed() ? "allow" : "deny"))
        .containsExactly("lookup_account=allow", "transfer_funds=deny", "read_document=allow");
  }

  @Test
  void rejectsRequestsWithoutAnAttemptToken() {
    String guestToken = api.newSessionToken();

    assertThat(postMcp(null).status()).isEqualTo(401);
    assertThat(postMcp(guestToken).status()).isEqualTo(401);
    assertThat(postMcp("not.a.jwt").status()).isEqualTo(401);
  }

  @Test
  void anAttemptTokenIsUselessOnThePlayerApi() {
    String attemptToken = attemptTokens.issue(attempt.id());

    assertThat(api.get(attemptToken, "/v1/accounts").status()).isEqualTo(401);
  }

  @Test
  void aFinishedAttemptsBriefCannotBeRead() {
    String token = attemptTokens.issue(attempt.id());
    attempts.finish(
        attempt.ref(), new AttemptStore.Finish(Outcome.REFUSED, 0, "x", "end_turn", 0, 0, false));

    try (McpTestClient mcp = McpTestClient.connect(port, token)) {
      assertThatThrownBy(mcp::brief).hasMessageContaining("not in progress");
    }
  }

  @Test
  void anotherAttemptsReviewIsInvisible() {
    try (McpTestClient mcp = McpTestClient.connect(port, attemptTokens.issue(attempt.id()))) {
      assertThatThrownBy(() -> mcp.read("bankjob://reviews/" + UUID.randomUUID()))
          .hasMessageContaining("No such pending review");
    }
  }

  private ApiClient.Response postMcp(String token) {
    String initialize =
        """
        {"jsonrpc": "2.0", "id": 1, "method": "initialize",
         "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                    "clientInfo": {"name": "test", "version": "1"}}}
        """;
    return api.send("POST", "/mcp", token, initialize);
  }
}
