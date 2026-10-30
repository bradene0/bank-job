package dev.bradenevans.bankjob.gameapi.play;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptRecord;
import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.attempt.PendingReviews;
import dev.bradenevans.bankjob.gameapi.attempt.PromptAssembler;
import dev.bradenevans.bankjob.gameapi.attempt.ToolCallRecord;
import dev.bradenevans.bankjob.gameapi.content.DailyPicks.HiddenNote;
import dev.bradenevans.bankjob.gameapi.content.GameContent;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailReviewer;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailVerdict;
import dev.bradenevans.bankjob.gameapi.policy.ToolRequest;
import dev.bradenevans.bankjob.gameapi.security.AttemptTokenService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Level 5's guardrail: assembles what the reviewer should see (server-side, like Penny's prompt),
 * parks it as a pending review, and asks the agent for a verdict. Every failure is a deny.
 */
@Component
@RegisterReflectionForBinding({
  ToolRequest.LookupAccount.class,
  ToolRequest.GetBalance.class,
  ToolRequest.VerifyIdentity.class,
  ToolRequest.ReadDocument.class,
  ToolRequest.UpdateContactInfo.class,
  ToolRequest.TransferFunds.class,
  ToolRequest.FlagForReview.class
}) // serialized into the review content
class GuardrailClient implements GuardrailReviewer {

  private static final Logger log = LoggerFactory.getLogger(GuardrailClient.class);

  private final AttemptStore attempts;
  private final PromptAssembler prompts;
  private final GameContent content;
  private final PendingReviews reviews;
  private final AttemptTokenService tokens;
  private final AgentClient agent;
  private final LlmBudget budget;
  private final JsonMapper json;

  GuardrailClient(
      AttemptStore attempts,
      PromptAssembler prompts,
      GameContent content,
      PendingReviews reviews,
      AttemptTokenService tokens,
      AgentClient agent,
      LlmBudget budget,
      JsonMapper json) {
    this.attempts = attempts;
    this.prompts = prompts;
    this.content = content;
    this.reviews = reviews;
    this.tokens = tokens;
    this.agent = agent;
    this.budget = budget;
    this.json = json;
  }

  @Override
  public GuardrailVerdict review(UUID attemptId, ToolRequest request) {
    Optional<AttemptRecord> attempt = attempts.find(attemptId);
    if (attempt.isEmpty()) {
      return GuardrailVerdict.unavailable("unknown attempt");
    }
    List<String> documentsRead = new ArrayList<>();
    StringBuilder documents = new StringBuilder();
    boolean claimsClearance = false;
    for (ToolCallRecord call : attempts.toolCalls(attemptId)) {
      if (call.allowed() && call.tool().equals("read_document") && call.result().has("body")) {
        String id = call.result().path("documentId").asString();
        Optional<HiddenNote> note = prompts.document(attempt.get(), id);
        if (note.isPresent()) {
          documentsRead.add(id);
          claimsClearance |= note.get().claimsClearance();
          documents.append('[').append(id).append("] ").append(note.get().title()).append(": ");
          documents.append(note.get().body()).append('\n');
        }
      }
    }
    String proposed = json.writeValueAsString(Map.of("tool", request.tool(), "arguments", request));
    String reviewContent =
        prompts.customerMessage(prompts.combo(attempt.get()))
            + "\n<documents_read>\n"
            + documents
            + "</documents_read>\n<proposed_tool_call>\n"
            + proposed
            + "\n</proposed_tool_call>";
    String toAccount = request instanceof ToolRequest.TransferFunds t ? t.toAccount() : null;
    PendingReviews.Review review =
        reviews.open(
            attemptId,
            content.guardrailSystemPrompt(),
            reviewContent,
            new PendingReviews.StubFacts(
                request.tool(), toAccount, List.copyOf(documentsRead), claimsClearance));
    try {
      AgentClient.ReviewResult result = agent.review(tokens.issue(attemptId), review.id());
      budget.record(result.usage());
      boolean allow = "allow".equals(result.verdict());
      return new GuardrailVerdict(allow, result.reason(), result.model());
    } catch (AgentClient.AgentUnavailableException e) {
      log.warn("Guardrail review failed for attempt {}: {}", attemptId, e.getMessage());
      return GuardrailVerdict.unavailable("the reviewer did not answer");
    } finally {
      reviews.close(review.id());
    }
  }
}
