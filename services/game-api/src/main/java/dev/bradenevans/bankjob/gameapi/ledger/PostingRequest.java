package dev.bradenevans.bankjob.gameapi.ledger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A journal entry to post. Validated here so callers get a clear error before any SQL runs; the
 * database enforces the same rules again at commit.
 */
public record PostingRequest(String description, AttemptRef attempt, List<Leg> legs) {

  public PostingRequest {
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description is required");
    }
    legs = List.copyOf(legs);
    if (legs.size() < 2) {
      throw new IllegalArgumentException("A journal entry needs at least two legs");
    }
    long sum = 0;
    Set<java.util.UUID> accounts = new HashSet<>();
    for (Leg leg : legs) {
      sum = Math.addExact(sum, leg.amountCents());
      if (!accounts.add(leg.accountId())) {
        throw new IllegalArgumentException("Account " + leg.accountId() + " appears twice");
      }
    }
    if (sum != 0) {
      throw new IllegalArgumentException("Legs must sum to zero, got " + sum);
    }
  }
}
