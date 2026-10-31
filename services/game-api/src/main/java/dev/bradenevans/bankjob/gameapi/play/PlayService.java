package dev.bradenevans.bankjob.gameapi.play;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptRecord;
import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.attempt.Outcome;
import dev.bradenevans.bankjob.gameapi.attempt.ToolCallRecord;
import dev.bradenevans.bankjob.gameapi.branch.BranchDirectory;
import dev.bradenevans.bankjob.gameapi.branch.BranchProvisioner;
import dev.bradenevans.bankjob.gameapi.content.Combo;
import dev.bradenevans.bankjob.gameapi.content.GameContent;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerQueries;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailReviewer;
import dev.bradenevans.bankjob.gameapi.policy.GuardrailVerdict;
import dev.bradenevans.bankjob.gameapi.security.AttemptTokenService;
import dev.bradenevans.bankjob.gameapi.session.SessionNerve;
import dev.bradenevans.bankjob.gameapi.tools.BankTools;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays one attempt end to end (README, "How it works"): spend nerve, then either replay a cached
 * model decision or run Penny through the agent, then decide the outcome from the ledger and the
 * recorded policy decisions.
 */
@Service
class PlayService {

  private static final Logger log = LoggerFactory.getLogger(PlayService.class);
  private static final TypeReference<Map<String, Object>> ARGS = new TypeReference<>() {};

  /** An attempt's result and everything the "Under the hood" panel needs. */
  record Played(
      AttemptRecord attempt,
      String traceId,
      Outcome outcome,
      long amountCents,
      String reply,
      boolean cached,
      Map<String, Integer> nerveRemaining,
      List<ToolCallRecord> toolCalls) {}

  private final SessionNerve nerve;
  private final AttemptStore attempts;
  private final ResponseCache cache;
  private final LlmBudget budget;
  private final LlmProperties llm;
  private final AttemptTokenService attemptTokens;
  private final AgentClient agent;
  private final BankTools tools;
  private final GuardrailReviewer guardrail;
  private final BranchDirectory directory;
  private final BranchProvisioner branches;
  private final GameContent content;
  private final LedgerQueries ledger;
  private final ReplyFilter replyFilter;
  private final JsonMapper json;
  private final Tracer tracer;
  // One attempt at a time per session: a second click cannot double-spend model calls.
  private final Set<UUID> sessionsInFlight = ConcurrentHashMap.newKeySet();

  PlayService(
      SessionNerve nerve,
      AttemptStore attempts,
      ResponseCache cache,
      LlmBudget budget,
      LlmProperties llm,
      AttemptTokenService attemptTokens,
      AgentClient agent,
      BankTools tools,
      GuardrailReviewer guardrail,
      BranchDirectory directory,
      BranchProvisioner branches,
      GameContent content,
      LedgerQueries ledger,
      ReplyFilter replyFilter,
      JsonMapper json,
      Tracer tracer) {
    this.nerve = nerve;
    this.attempts = attempts;
    this.cache = cache;
    this.budget = budget;
    this.llm = llm;
    this.attemptTokens = attemptTokens;
    this.agent = agent;
    this.tools = tools;
    this.guardrail = guardrail;
    this.directory = directory;
    this.branches = branches;
    this.content = content;
    this.ledger = ledger;
    this.replyFilter = replyFilter;
    this.json = json;
    this.tracer = tracer;
  }

  Played play(UUID sessionId, int level, Combo combo) {
    if (!sessionsInFlight.add(sessionId)) {
      throw new PlayExceptions.AttemptInProgressException();
    }
    try {
      return playExclusively(sessionId, level, combo);
    } finally {
      sessionsInFlight.remove(sessionId);
    }
  }

  private Played playExclusively(UUID sessionId, int level, Combo combo) {
    SessionNerve.Spent spent =
        nerve
            .spend(sessionId, level)
            .orElseThrow(() -> new PlayExceptions.NerveExhaustedException(level));
    // A session keeps its branch across levels; only the vault is restocked on a level's first
    // attempt (ADR-0016). A refunded attempt makes the next one "first" again, which is harmless:
    // the restock only fills a shortfall.
    long restockedCents = 0;
    if (spent.nerveRemaining().get(Integer.toString(level)) == content.level(level).nerve() - 1) {
      restockedCents = branches.restockVault(sessionId, level);
    }
    var cached = cache.find(level, spent.dailySeed(), combo.id());
    if (cached.isEmpty() && llm.live() && budget.exhausted()) {
      nerve.refund(sessionId, level);
      throw new PlayExceptions.LivePlayUnavailableException(
          "over_budget", "Penny is on her break (this month's model budget is spent).");
    }

    Span span = tracer.currentSpan();
    String traceId = span == null ? null : span.context().traceId();
    AttemptRecord attempt =
        attempts.create(
            sessionId,
            level,
            combo.id(),
            json.valueToTree(tiles(combo)),
            spent.dailySeed(),
            traceId);
    if (span != null) {
      span.tag("bankjob.attempt_id", attempt.id().toString());
      span.tag("bankjob.level", level);
      span.tag("bankjob.combo_id", combo.id());
      if (restockedCents > 0) {
        span.tag("bankjob.vault_restocked_cents", restockedCents);
      }
    }

    String reply;
    String stopReason;
    String runModel = null;
    AgentClient.Usage usage = AgentClient.Usage.NONE;
    if (cached.isPresent()) {
      replay(attempt, cached.get());
      reply = cached.get().reply();
      stopReason = cached.get().stopReason();
    } else {
      AgentClient.RunResult run;
      try {
        run = agent.run(attemptTokens.issue(attempt.id()));
      } catch (AgentClient.AgentUnavailableException e) {
        log.warn("Attempt {} failed: {}", attempt.id(), e.getMessage());
        attempts.finish(
            attempt.ref(),
            new AttemptStore.Finish(Outcome.ERROR, 0, null, "agent_unavailable", 0, 0, false));
        nerve.refund(sessionId, level);
        throw new PlayExceptions.LivePlayUnavailableException(
            "agent_unavailable", "Penny is away from her desk right now.");
      }
      budget.record(run.usage());
      reply = run.reply();
      stopReason = run.stopReason();
      runModel = run.model();
      usage = run.usage();
    }

    List<ToolCallRecord> calls = attempts.toolCalls(attempt.id());
    long amount = muleCredit(attempt);
    Outcome outcome = decide(amount, calls);
    String filteredReply = replyFilter.filter(reply);
    attempts.finish(
        attempt.ref(),
        new AttemptStore.Finish(
            outcome,
            amount,
            filteredReply,
            stopReason,
            usage.inputTokens(),
            usage.outputTokens(),
            cached.isPresent()));
    if (cached.isEmpty() && cacheable(runModel)) {
      cache.store(level, spent.dailySeed(), combo.id(), transcript(calls, reply, stopReason));
    }
    if (span != null) {
      span.tag("bankjob.outcome", outcome.wire());
      span.tag("bankjob.cached", cached.isPresent());
    }
    return new Played(
        attempt,
        traceId,
        outcome,
        amount,
        filteredReply,
        cached.isPresent(),
        spent.nerveRemaining(),
        calls);
  }

  /** Only cache decisions from the model the cache key names (guards a mode mismatch). */
  private boolean cacheable(String runModel) {
    if (llm.cacheModel().equals(runModel)) {
      return true;
    }
    log.warn(
        "Agent answered as {} but game-api expects {}; not caching", runModel, llm.cacheModel());
    return false;
  }

  /**
   * Re-runs the model's recorded decisions through the policy engine and this session's ledger.
   * Recorded guardrail verdicts are reused. A call the guardrail never saw when the decision was
   * recorded (another check stopped it first, in that session's state) is reviewed now, so a
   * session whose state gets it further, such as one carrying a Level 4 phone change (ADR-0016),
   * meets the same guardrail as a live attempt would. Over the model budget, that fails closed.
   */
  private void replay(AttemptRecord attempt, Transcript transcript) {
    for (Transcript.Call call : transcript.calls()) {
      GuardrailReviewer reviewer =
          (attemptId, request) -> {
            if (call.review() != null) {
              return call.review();
            }
            if (llm.live() && budget.exhausted()) {
              return GuardrailVerdict.unavailable("over the model budget");
            }
            return guardrail.review(attemptId, request);
          };
      tools.call(attempt.id(), call.tool(), call.args(), reviewer);
    }
  }

  private long muleCredit(AttemptRecord attempt) {
    return directory
        .holder(attempt.sessionId(), BranchDirectory.MULE_NUMBER)
        .map(mule -> ledger.attemptNetCents(attempt.ref(), mule.accountId()))
        .orElse(0L);
  }

  /** The ledger decides a win; policy decisions and tool use decide blocked versus refused. */
  static Outcome decide(long amountCents, List<ToolCallRecord> calls) {
    if (amountCents > 0) {
      return Outcome.WON;
    }
    boolean triedSomething =
        calls.stream()
            .anyMatch(
                c -> c.tool().equals("transfer_funds") || c.tool().equals("update_contact_info"));
    return triedSomething ? Outcome.BLOCKED : Outcome.REFUSED;
  }

  private Transcript transcript(List<ToolCallRecord> calls, String reply, String stopReason) {
    List<Transcript.Call> recorded =
        calls.stream()
            .map(
                c ->
                    new Transcript.Call(
                        c.tool(),
                        json.convertValue(c.args(), ARGS),
                        c.review() == null
                            ? null
                            : json.convertValue(c.review(), GuardrailVerdict.class)))
            .toList();
    return new Transcript(recorded, reply, stopReason);
  }

  private static Map<String, String> tiles(Combo combo) {
    return Map.of(
        "persona", combo.persona().id(),
        "pretext", combo.pretext().id(),
        "technique", combo.technique().id(),
        "payload", combo.payload().id());
  }
}
