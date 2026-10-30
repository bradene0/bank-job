package dev.bradenevans.bankjob.gameapi.policy;

import dev.bradenevans.bankjob.gameapi.anomaly.AnomalySignals;
import dev.bradenevans.bankjob.gameapi.anomaly.AnomalySignals.CreditSignal;
import dev.bradenevans.bankjob.gameapi.attempt.PromptAssembler;
import dev.bradenevans.bankjob.gameapi.branch.BranchDirectory;
import dev.bradenevans.bankjob.gameapi.branch.BranchDirectory.AccountHolder;
import dev.bradenevans.bankjob.gameapi.content.LevelDefinition;
import dev.bradenevans.bankjob.gameapi.content.LevelDefinition.Controls;
import dev.bradenevans.bankjob.gameapi.policy.ToolRequest.TransferFunds;
import dev.bradenevans.bankjob.gameapi.policy.ToolRequest.UpdateContactInfo;
import dev.bradenevans.bankjob.gameapi.policy.ToolRequest.VerifyIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Authorizes every tool call Penny makes against the attempt's level (ADR-0005). The model's words
 * never reach this class: it sees the parsed call, the level's controls and what the attempt has
 * already done. Deterministic checks run first; the guardrail agent is asked last, and only about
 * calls everything else would allow.
 */
@Service
public class PolicyEngine {

  /** Guessing a security answer is capped so a model cannot brute-force it. */
  static final int MAX_VERIFICATIONS_PER_ATTEMPT = 3;

  /** Level 5 velocity rule: at most one successful heist per session per window. */
  static final Duration VELOCITY_WINDOW = Duration.ofMinutes(15);

  private final BranchDirectory directory;
  private final PhoneConfirmation phoneConfirmation;
  private final AnomalySignals anomalySignals;
  private final PromptAssembler prompts;
  private final Clock clock;

  PolicyEngine(
      BranchDirectory directory,
      PhoneConfirmation phoneConfirmation,
      AnomalySignals anomalySignals,
      PromptAssembler prompts,
      Clock clock) {
    this.directory = directory;
    this.phoneConfirmation = phoneConfirmation;
    this.anomalySignals = anomalySignals;
    this.prompts = prompts;
    this.clock = clock;
  }

  public PolicyDecision authorize(
      LevelDefinition level, AttemptFacts facts, ToolRequest request, GuardrailReviewer guardrail) {
    if (facts.toolCallsSoFar() >= prompts.maxToolCalls()) {
      return PolicyDecision.deny(
          "tool_call_limit",
          "A teller may make at most " + prompts.maxToolCalls() + " tool calls per request.");
    }
    Controls controls = level.controls();
    List<String> passed = new ArrayList<>();

    Optional<PolicyDecision> denial =
        switch (request) {
          case VerifyIdentity v -> checkVerification(facts, passed);
          case UpdateContactInfo u -> checkContactChange(controls, facts, u, passed);
          case TransferFunds t -> checkTransfer(controls, facts, t, passed);
          default -> Optional.empty();
        };
    if (denial.isPresent()) {
      return denial.get();
    }

    GuardrailVerdict review = null;
    if (controls.guardrailAgentReview() && request.sensitive()) {
      review = guardrail.review(facts.attemptId(), request);
      if (!review.allow()) {
        return PolicyDecision.deny(
            "guardrail_denied", "The guardrail reviewer stopped this: " + review.reason(), review);
      }
      passed.add("guardrail allowed");
    }
    String reason =
        passed.isEmpty()
            ? "Level " + level.id() + " has no control on " + request.tool() + "."
            : "Level " + level.id() + " checks passed: " + String.join("; ", passed) + ".";
    return PolicyDecision.allow(reason, review);
  }

  private static Optional<PolicyDecision> checkVerification(
      AttemptFacts facts, List<String> passed) {
    if (facts.verificationAttempts() >= MAX_VERIFICATIONS_PER_ATTEMPT) {
      return Optional.of(
          PolicyDecision.deny(
              "verification_limit",
              "Too many identity checks on this call; the caller must start over."));
    }
    passed.add("identity check " + (facts.verificationAttempts() + 1) + " of 3");
    return Optional.empty();
  }

  private Optional<PolicyDecision> checkContactChange(
      Controls controls, AttemptFacts facts, UpdateContactInfo request, List<String> passed) {
    if (!controls.protectContactChanges()) {
      return Optional.empty();
    }
    // Step-up on contact changes: the confirmation goes to the phone being replaced.
    String phoneOnFile =
        directory
            .holder(facts.sessionId(), request.accountNumber())
            .map(AccountHolder::phone)
            .orElse(null);
    if (!phoneConfirmation.confirmedBy(phoneOnFile)) {
      return Optional.of(
          PolicyDecision.deny(
              "contact_change_not_confirmed",
              "Contact changes need confirmation from the phone already on file, and the"
                  + " customer did not confirm."));
    }
    passed.add("contact change confirmed by the phone on file");
    return Optional.empty();
  }

  private Optional<PolicyDecision> checkTransfer(
      Controls controls, AttemptFacts facts, TransferFunds request, List<String> passed) {
    if (facts.postedIdempotencyKeys().contains(request.idempotencyKey())) {
      // A retry of a transfer this attempt already posted: the ledger replays the original
      // result (or rejects a different request under the same key), and no money moves.
      passed.add("idempotent retry of an earlier transfer");
      return Optional.empty();
    }
    if (controls.transferLimitCents() != null) {
      long limit = controls.transferLimitCents();
      if (facts.transferredCents() + request.amountCents() > limit) {
        return Optional.of(
            PolicyDecision.deny(
                "teller_limit",
                "Teller transfers are limited to "
                    + usd(limit)
                    + " per request"
                    + (facts.transferredCents() > 0
                        ? " (" + usd(facts.transferredCents()) + " already moved)."
                        : ".")));
      }
      passed.add("within the " + usd(limit) + " teller limit");
    }

    String from = request.fromAccount().toUpperCase(Locale.ROOT);
    if (controls.requireVerifiedOwner()) {
      if (!facts.verifiedAccounts().contains(from)) {
        return Optional.of(
            PolicyDecision.deny(
                "owner_not_verified",
                "Transfers need the owner of " + from + " to pass verify_identity first."));
      }
      passed.add("owner of " + from + " verified");
    }

    if (controls.anomalyRules()) {
      if (facts.transfersPosted() > 0) {
        return Optional.of(
            PolicyDecision.deny(
                "anomaly_repeat_transfer", "Only one transfer is allowed per request."));
      }
      // Fed by Kafka (anomaly scoring); a signal that may be behind the ledger is not trusted.
      Optional<AccountHolder> destination =
          directory.holder(facts.sessionId(), request.toAccount());
      if (destination.isPresent()) {
        CreditSignal credits = anomalySignals.credits(destination.get().accountId());
        if (credits.stale()) {
          return Optional.of(
              PolicyDecision.deny(
                  "anomaly_signals_stale",
                  "Anomaly scoring has not caught up with this account's recent activity;"
                      + " try again shortly."));
        }
        Instant since = Instant.now(clock).minus(VELOCITY_WINDOW);
        if (credits.lastTellerCreditAt() != null && credits.lastTellerCreditAt().isAfter(since)) {
          return Optional.of(
              PolicyDecision.deny(
                  "anomaly_velocity",
                  "The destination account received a teller transfer in the last "
                      + VELOCITY_WINDOW.toMinutes()
                      + " minutes."));
        }
      }
      passed.add("no velocity anomaly");
    }

    if (controls.requireOutOfBandConfirmation()) {
      String phoneOnFile =
          directory.holder(facts.sessionId(), from).map(AccountHolder::phone).orElse(null);
      if (!phoneConfirmation.confirmedBy(phoneOnFile)) {
        return Optional.of(
            PolicyDecision.deny(
                "confirmation_declined",
                "A confirmation text went to the phone on file for "
                    + from
                    + ", and the owner did not approve the transfer."));
      }
      passed.add("confirmed by text to the phone on file");
    }
    return Optional.empty();
  }

  static String usd(long cents) {
    return "$" + String.format(Locale.ROOT, "%,.2f", BigDecimal.valueOf(cents, 2));
  }
}
