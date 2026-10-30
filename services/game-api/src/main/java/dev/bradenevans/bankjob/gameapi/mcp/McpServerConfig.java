package dev.bradenevans.bankjob.gameapi.mcp;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptRecord;
import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.attempt.PendingReviews;
import dev.bradenevans.bankjob.gameapi.attempt.PromptAssembler;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailReviewer;
import dev.bradenevans.bankjob.gameapi.tools.BankTools;
import dev.bradenevans.bankjob.gameapi.tools.BankTools.ToolOutcome;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import tools.jackson.databind.json.JsonMapper;

/**
 * game-api's MCP server: Penny's bank tools plus the resources she reads (her brief, and guardrail
 * reviews). Stateless streamable HTTP at /mcp; Spring Security has already validated the attempt
 * token before a request gets here, and the token's subject names the attempt.
 */
@Configuration
@ImportRuntimeHints(McpRuntimeHints.class)
@RegisterReflectionForBinding({
  PromptAssembler.Brief.class,
  PromptAssembler.StubFacts.class,
  PendingReviews.StubFacts.class
})
class McpServerConfig {

  static final String ENDPOINT = "/mcp";
  static final String BRIEF_URI = "bankjob://attempt/brief";
  static final String REVIEW_URI_PREFIX = "bankjob://reviews/";
  private static final String ATTEMPT_ID = "attemptId";
  private static final int MAX_REQUEST_BYTES = 64 * 1024;

  @Bean
  McpJsonMapper mcpJsonMapper(JsonMapper json) {
    return new JacksonMcpJsonMapper(json);
  }

  @Bean
  HttpServletStatelessServerTransport mcpTransport(McpJsonMapper mcpJson) {
    return HttpServletStatelessServerTransport.builder()
        .jsonMapper(mcpJson)
        .messageEndpoint(ENDPOINT)
        .maxRequestSize(MAX_REQUEST_BYTES)
        .contextExtractor(McpServerConfig::attemptContext)
        .build();
  }

  @Bean
  ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
      HttpServletStatelessServerTransport transport) {
    return new ServletRegistrationBean<>(transport, ENDPOINT);
  }

  @Bean
  McpStatelessSyncServer mcpServer(
      HttpServletStatelessServerTransport transport,
      McpJsonMapper mcpJson,
      JsonMapper json,
      BankTools tools,
      GuardrailReviewer guardrail,
      AttemptStore attempts,
      PromptAssembler prompts,
      PendingReviews reviews) {
    List<SyncToolSpecification> toolSpecs =
        BankToolCatalog.TOOLS.stream()
            .map(
                def ->
                    SyncToolSpecification.builder()
                        .tool(
                            McpSchema.Tool.builder()
                                .name(def.name())
                                .description(def.description())
                                .inputSchema(mcpJson, def.inputSchema())
                                .build())
                        .callHandler(
                            (ctx, request) -> {
                              ToolOutcome outcome =
                                  tools.call(
                                      attemptId(ctx), def.name(), request.arguments(), guardrail);
                              return CallToolResult.builder()
                                  .addTextContent(json.writeValueAsString(outcome.body()))
                                  .isError(outcome.isError())
                                  .build();
                            })
                        .build())
            .toList();

    SyncResourceSpecification brief =
        new SyncResourceSpecification(
            McpSchema.Resource.builder()
                .uri(BRIEF_URI)
                .name("attempt-brief")
                .description("The attempt Penny is handling: prompts and stub facts.")
                .mimeType("application/json")
                .build(),
            (ctx, request) -> {
              AttemptRecord attempt =
                  attempts
                      .find(attemptId(ctx))
                      .filter(AttemptRecord::inFlight)
                      .orElseThrow(() -> invalid("This attempt is not in progress."));
              return jsonResource(json, BRIEF_URI, prompts.brief(attempt));
            });

    SyncResourceTemplateSpecification review =
        new SyncResourceTemplateSpecification(
            McpSchema.ResourceTemplate.builder(REVIEW_URI_PREFIX + "{reviewId}", "guardrail-review")
                .description("A tool call awaiting the guardrail's verdict.")
                .mimeType("application/json")
                .build(),
            (ctx, request) -> {
              UUID reviewId = reviewId(request.uri());
              PendingReviews.Review pending =
                  reviews
                      .find(reviewId, attemptId(ctx))
                      .orElseThrow(() -> invalid("No such pending review."));
              return jsonResource(
                  json,
                  request.uri(),
                  Map.of(
                      "reviewId", pending.id(),
                      "system", pending.system(),
                      "content", pending.content(),
                      "stub", pending.stub()));
            });

    return McpServer.sync(transport)
        .serverInfo("bankjob-game-api", "0.3.0")
        .capabilities(
            McpSchema.ServerCapabilities.builder().tools(false).resources(false, false).build())
        .immediateExecution(true) // run handlers on the request thread
        .tools(toolSpecs)
        .resources(brief)
        .resourceTemplates(review)
        .build();
  }

  /** Puts the authenticated attempt id into the MCP context for the handlers. */
  private static McpTransportContext attemptContext(jakarta.servlet.http.HttpServletRequest req) {
    Principal principal = req.getUserPrincipal();
    return principal == null
        ? McpTransportContext.EMPTY
        : McpTransportContext.create(Map.of(ATTEMPT_ID, principal.getName()));
  }

  private static UUID attemptId(McpTransportContext ctx) {
    Object id = ctx.get(ATTEMPT_ID);
    if (id == null) {
      throw invalid("Missing attempt token.");
    }
    return UUID.fromString(id.toString());
  }

  private static UUID reviewId(String uri) {
    try {
      return UUID.fromString(uri.substring(REVIEW_URI_PREFIX.length()));
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      throw invalid("Malformed review URI.");
    }
  }

  private static ReadResourceResult jsonResource(JsonMapper json, String uri, Object body) {
    return new ReadResourceResult(
        List.of(new TextResourceContents(uri, "application/json", json.writeValueAsString(body))));
  }

  private static McpError invalid(String message) {
    return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message(message).build();
  }
}
