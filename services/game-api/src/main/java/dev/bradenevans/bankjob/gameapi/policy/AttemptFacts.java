package dev.bradenevans.bankjob.gameapi.policy;

import java.util.Set;
import java.util.UUID;

/**
 * What has happened so far in one attempt, derived from its recorded tool calls. Verification lasts
 * for one attempt only: a new attempt is a new phone call.
 */
public record AttemptFacts(
    UUID sessionId,
    UUID attemptId,
    int toolCallsSoFar,
    Set<String> verifiedAccounts,
    int verificationAttempts,
    long transferredCents,
    int transfersPosted,
    Set<String> postedIdempotencyKeys) {}
