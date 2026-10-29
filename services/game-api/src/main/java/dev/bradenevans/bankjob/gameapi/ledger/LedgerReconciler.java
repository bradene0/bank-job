package dev.bradenevans.bankjob.gameapi.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Independent checks of the ledger invariants, computed from raw postings rather than from the
 * balances the service maintains. Used by tests now and by a scheduled job later.
 */
@Component
public class LedgerReconciler {

  /** An account whose stored balance disagrees with the sum of its postings. */
  public record BalanceMismatch(UUID accountId, long storedCents, long postedCents) {}

  private final JdbcClient jdbc;

  LedgerReconciler(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every mismatch in the database; for the scheduled reconciliation job. */
  public List<BalanceMismatch> balanceMismatches() {
    return balanceMismatches(null);
  }

  /** Mismatches within one session (or all sessions when {@code sessionId} is null). */
  public List<BalanceMismatch> balanceMismatches(UUID sessionId) {
    return jdbc.sql(
            """
            SELECT b.account_id, b.balance_cents, coalesce(sum(p.amount_cents), 0) AS posted
              FROM account_balances b
              JOIN accounts a ON a.id = b.account_id
              LEFT JOIN postings p ON p.account_id = b.account_id
             WHERE CAST(:sessionId AS uuid) IS NULL OR a.session_id = :sessionId
             GROUP BY b.account_id, b.balance_cents
            HAVING b.balance_cents <> coalesce(sum(p.amount_cents), 0)
            """)
        .param("sessionId", sessionId)
        .query(
            (rs, i) ->
                new BalanceMismatch(
                    rs.getObject("account_id", UUID.class),
                    rs.getLong("balance_cents"),
                    rs.getLong("posted")))
        .list();
  }

  /** Entries that are unbalanced or have fewer than two postings (should always be empty). */
  public List<UUID> invalidJournalEntries() {
    return jdbc.sql(
            """
            SELECT e.id
              FROM journal_entries e
              LEFT JOIN postings p ON p.entry_id = e.id
             GROUP BY e.id
            HAVING count(p.id) < 2 OR coalesce(sum(p.amount_cents), 0) <> 0
            """)
        .query(UUID.class)
        .list();
  }

  /** Sum of all balances in a session. Double entry means it is always exactly zero. */
  public long sessionNetCents(UUID sessionId) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(b.balance_cents), 0)
              FROM account_balances b
              JOIN accounts a ON a.id = b.account_id
             WHERE a.session_id = :sessionId
            """)
        .param("sessionId", sessionId)
        .query(Long.class)
        .single();
  }
}
