package dev.bradenevans.bankjob.gameapi.ledger;

import java.util.UUID;

/** Read-only view of an account and its current balance. */
public record AccountSnapshot(
    UUID id,
    UUID sessionId,
    UUID customerId,
    String number,
    AccountKind kind,
    String currency,
    long balanceCents) {}
