package dev.bradenevans.bankjob.gameapi.ledger;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only questions about posted money. Outcomes are decided from these, not from the model. */
@Service
public class LedgerQueries {

  private final JdbcClient jdbc;

  LedgerQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Net change to an account from the journal entries an attempt posted. */
  @Transactional(readOnly = true)
  public long attemptNetCents(AttemptRef attempt, UUID accountId) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(p.amount_cents), 0)
            FROM postings p JOIN journal_entries e ON e.id = p.entry_id
            WHERE p.account_id = :account
              AND e.attempt_id = :attempt AND e.attempt_created_at = :created
            """)
        .param("account", accountId)
        .param("attempt", attempt.id())
        .param("created", Timestamp.from(attempt.createdAt()))
        .query(Long.class)
        .single();
  }
}
