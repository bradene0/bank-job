package dev.bradenevans.bankjob.gameapi.ledger;

import java.time.Instant;
import java.util.UUID;

/** Reference to a row in the partitioned attempts table, whose key is (id, created_at). */
public record AttemptRef(UUID id, Instant createdAt) {}
