package dev.bradenevans.bankjob.gameapi.tools;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptRecord;
import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.attempt.PromptAssembler;
import dev.bradenevans.bankjob.gameapi.attempt.ToolCallRecord;
import dev.bradenevans.bankjob.gameapi.branch.BranchDirectory;
import dev.bradenevans.bankjob.gameapi.branch.BranchDirectory.AccountHolder;
import dev.bradenevans.bankjob.gameapi.content.GameContent;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerExceptions.IdempotencyConflictException;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerService;
import dev.bradenevans.bankjob.gameapi.ledger.TransferCommand;
import dev.bradenevans.bankjob.gameapi.ledger.TransferResult;
import dev.bradenevans.bankjob.gameapi.policy.AttemptFacts;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailReviewer;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailVerdict;
import dev.bradenevans.bankjob.gameapi.policy.PolicyDecision;
import dev.bradenevans.bankjob.gameapi.policy.PolicyEngine;
import dev.bradenevans.bankjob.gameapi.policy.ToolRequest;
import dev.bradenevans.bankjob.gameapi.tools.ToolArguments.InvalidArgumentsException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes Penny's bank tools. Every call, allowed or not, valid or not, is authorized by the
 * policy engine and recorded with its decision before the result goes back to the model.
 *
 * <p>Calls within one attempt are serialized, so per-attempt limits cannot be raced by parallel
 * tool calls. The lock is in-process: game-api runs as a single replica (ADR-0001).
 */
@Service
@RegisterReflectionForBinding(GuardrailVerdict.class) // stored as JSON in tool_calls.review
public class BankTools {

  /** The tool's answer, as JSON, and whether the model should treat it as an error. */
  public record ToolOutcome(boolean isError, JsonNode body) {}

  private static final int LOCK_STRIPES = 64;

  /** Must match the tool_calls_tool_known check constraint (V4). */
  public static final Set<String> TOOLS =
      Set.of(
          "lookup_account",
          "get_balance",
          "verify_identity",
          "read_document",
          "update_contact_info",
          "transfer_funds",
          "flag_for_review");

  private final AttemptStore attempts;
  private final PromptAssembler prompts;
  private final GameContent content;
  private final PolicyEngine policy;
  private final BranchDirectory directory;
  private final LedgerService ledger;
  private final JsonMapper json;
  private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

  BankTools(
      AttemptStore attempts,
      PromptAssembler prompts,
      GameContent content,
      PolicyEngine policy,
      BranchDirectory directory,
      LedgerService ledger,
      JsonMapper json) {
    this.attempts = attempts;
    this.prompts = prompts;
    this.content = content;
    this.policy = policy;
    this.directory = directory;
    this.ledger = ledger;
    this.json = json;
    for (int i = 0; i < LOCK_STRIPES; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  public ToolOutcome call(
      UUID attemptId, String tool, Map<String, Object> args, GuardrailReviewer guardrail) {
    Optional<AttemptRecord> found = attempts.find(attemptId).filter(AttemptRecord::inFlight);
    if (found.isEmpty()) {
      // Nothing to record against: the attempt is over (or never existed).
      return error("attempt_closed", "This request has already been completed.");
    }
    if (!TOOLS.contains(tool)) {
      throw new IllegalArgumentException("Not a bank tool: " + tool); // MCP routes known names only
    }
    AttemptRecord attempt = found.get();
    ReentrantLock lock = locks[Math.floorMod(attemptId.hashCode(), LOCK_STRIPES)];
    lock.lock();
    try {
      return callLocked(attempt, tool, args, guardrail);
    } finally {
      lock.unlock();
    }
  }

  private ToolOutcome callLocked(
      AttemptRecord attempt, String tool, Map<String, Object> args, GuardrailReviewer guardrail) {
    long started = System.nanoTime();
    List<ToolCallRecord> history = attempts.toolCalls(attempt.id());
    int ordinal = history.size() + 1;
    JsonNode argsJson = json.valueToTree(args == null ? Map.of() : args);

    ToolRequest request;
    try {
      request = ToolArguments.parse(tool, args);
    } catch (InvalidArgumentsException e) {
      ToolOutcome outcome = error("invalid_arguments", e.getMessage());
      record(
          attempt,
          ordinal,
          tool,
          argsJson,
          false,
          "invalid_arguments: " + e.getMessage(),
          outcome,
          null,
          started);
      return outcome;
    }

    PolicyDecision decision =
        policy.authorize(
            content.level(attempt.level()), facts(attempt, history), request, guardrail);
    ToolOutcome outcome =
        decision.allowed() ? execute(attempt, request) : error("denied", decision.reason());
    record(
        attempt,
        ordinal,
        request.tool(),
        argsJson,
        decision.allowed(),
        decision.recorded(),
        outcome,
        decision.review(),
        started);
    return outcome;
  }

  private AttemptFacts facts(AttemptRecord attempt, List<ToolCallRecord> history) {
    Set<String> verified = new HashSet<>();
    int verifications = 0;
    long transferred = 0;
    int transfers = 0;
    Set<String> postedKeys = new HashSet<>();
    for (ToolCallRecord call : history) {
      if (!call.allowed()) {
        continue;
      }
      switch (call.tool()) {
        case "verify_identity" -> {
          verifications++;
          if (call.result().path("verified").asBoolean(false)) {
            verified.add(call.result().path("accountNumber").asString());
          }
        }
        case "transfer_funds" -> {
          if ("posted".equals(call.result().path("status").asString())
              && !call.result().path("replayed").asBoolean(false)) {
            transferred += call.result().path("amountCents").asLong();
            transfers++;
            postedKeys.add(call.args().path("idempotency_key").asString());
          }
        }
        default -> {}
      }
    }
    return new AttemptFacts(
        attempt.sessionId(),
        attempt.id(),
        history.size(),
        Set.copyOf(verified),
        verifications,
        transferred,
        transfers,
        Set.copyOf(postedKeys));
  }

  private ToolOutcome execute(AttemptRecord attempt, ToolRequest request) {
    UUID session = attempt.sessionId();
    return switch (request) {
      case ToolRequest.LookupAccount r -> {
        ObjectNode body = json.createObjectNode();
        var matches = body.putArray("matches");
        for (AccountHolder h : directory.lookup(session, r.accountHint())) {
          ObjectNode m = matches.addObject();
          m.put("accountNumber", h.accountNumber());
          m.put("kind", h.kind().name().toLowerCase(Locale.ROOT));
          m.put("owner", h.ownerName());
          m.put("phoneMasked", mask(h.phone()));
          m.put("securityQuestion", h.securityQuestion());
          m.put("answerHint", h.answerHint());
        }
        yield ok(body);
      }
      case ToolRequest.GetBalance r ->
          withAccount(
              session,
              r.accountNumber(),
              h -> {
                ObjectNode body = json.createObjectNode();
                body.put("accountNumber", h.accountNumber());
                body.put("balanceCents", h.balanceCents());
                body.put("balanceUsd", BigDecimal.valueOf(h.balanceCents(), 2).toPlainString());
                return ok(body);
              });
      case ToolRequest.VerifyIdentity r ->
          withAccount(
              session,
              r.accountNumber(),
              h -> {
                boolean verified =
                    h.customerId() != null && directory.answerMatches(h.customerId(), r.answer());
                ObjectNode body = json.createObjectNode();
                body.put("accountNumber", h.accountNumber());
                body.put("verified", verified);
                return ok(body);
              });
      case ToolRequest.ReadDocument r ->
          prompts
              .document(attempt, r.documentId())
              .map(
                  note -> {
                    ObjectNode body = json.createObjectNode();
                    body.put("documentId", r.documentId());
                    body.put("title", note.title());
                    body.put("body", note.body());
                    return ok(body);
                  })
              .orElseGet(() -> error("not_found", "No document " + r.documentId() + " here."));
      case ToolRequest.UpdateContactInfo r ->
          withAccount(
              session,
              r.accountNumber(),
              h -> {
                if (h.customerId() == null) {
                  return error("no_owner", h.accountNumber() + " has no customer on file.");
                }
                directory.changePhone(h.customerId(), r.phone());
                ObjectNode body = json.createObjectNode();
                body.put("accountNumber", h.accountNumber());
                body.put("phone", r.phone());
                return ok(body);
              });
      case ToolRequest.TransferFunds r -> transfer(attempt, r);
      case ToolRequest.FlagForReview r -> {
        ObjectNode body = json.createObjectNode();
        body.put("flagged", true);
        yield ok(body);
      }
    };
  }

  private ToolOutcome transfer(AttemptRecord attempt, ToolRequest.TransferFunds r) {
    Optional<AccountHolder> from = directory.holder(attempt.sessionId(), r.fromAccount());
    Optional<AccountHolder> to = directory.holder(attempt.sessionId(), r.toAccount());
    if (from.isEmpty() || to.isEmpty()) {
      return error("unknown_account", "Both accounts must exist at this branch.");
    }
    if (from.get().accountId().equals(to.get().accountId())) {
      return error("invalid_arguments", "from_account and to_account must differ.");
    }
    TransferResult result;
    try {
      result =
          ledger.transfer(
              new TransferCommand(
                  attempt.sessionId(),
                  from.get().accountId(),
                  to.get().accountId(),
                  r.amountCents(),
                  "Teller transfer, attempt " + attempt.id(),
                  attempt.ref(),
                  // Scoped to the attempt, so a key the model reuses elsewhere cannot collide.
                  "attempt:" + attempt.id() + ":" + r.idempotencyKey()));
    } catch (IdempotencyConflictException e) {
      return error(
          "idempotency_conflict", "That idempotency_key was used for a different transfer.");
    }
    ObjectNode body = json.createObjectNode();
    body.put(
        "status",
        result.status() == TransferResult.Status.POSTED ? "posted" : "insufficient_funds");
    body.put("amountCents", r.amountCents());
    body.put("fromAccount", from.get().accountNumber());
    body.put("toAccount", to.get().accountNumber());
    body.put("replayed", result.replayed());
    return new ToolOutcome(result.status() != TransferResult.Status.POSTED, body);
  }

  private ToolOutcome withAccount(
      UUID session, String number, java.util.function.Function<AccountHolder, ToolOutcome> then) {
    return directory
        .holder(session, number)
        .map(then)
        .orElseGet(() -> error("unknown_account", "No account " + number + " at this branch."));
  }

  private static String mask(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("[^0-9]", "");
    return "***-***-" + digits.substring(Math.max(0, digits.length() - 4));
  }

  private ToolOutcome ok(ObjectNode body) {
    return new ToolOutcome(false, body);
  }

  private ToolOutcome error(String code, String reason) {
    ObjectNode body = json.createObjectNode();
    body.put("error", code);
    body.put("reason", reason);
    return new ToolOutcome(true, body);
  }

  private void record(
      AttemptRecord attempt,
      int ordinal,
      String tool,
      JsonNode args,
      boolean allowed,
      String reason,
      ToolOutcome outcome,
      GuardrailVerdict review,
      long startedNanos) {
    JsonNode reviewJson = review == null ? null : json.valueToTree(review);
    int latencyMs =
        (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
    attempts.recordToolCall(
        attempt.ref(),
        new ToolCallRecord(
            ordinal, tool, args, allowed, reason, outcome.body(), reviewJson, latencyMs));
  }
}
