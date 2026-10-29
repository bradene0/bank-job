package dev.bradenevans.bankjob.gameapi.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import dev.bradenevans.bankjob.gameapi.support.TestBranches;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database enforces the ledger invariants on its own, even for SQL that bypasses the Java
 * service. These tests write raw SQL to prove it.
 */
@IntegrationTest
class LedgerDatabaseConstraintsIT {

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate transactions;
  @Autowired TestBranches branches;

  UUID vault;
  UUID mule;

  @BeforeEach
  void setUp() {
    Map<String, AccountSnapshot> accounts = branches.accounts(branches.newSession());
    vault = accounts.get("BV-VAULT-01").id();
    mule = accounts.get("BV-MULE-01").id();
  }

  @Test
  void unbalancedEntryIsRejectedAtCommit() {
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    s -> {
                      UUID entry = insertEntry();
                      insertPosting(entry, vault, -500);
                      insertPosting(entry, mule, 400); // statement succeeds; commit must not
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("unbalanced");
  }

  @Test
  void entryWithASinglePostingIsRejected() {
    assertThatThrownBy(
            () -> transactions.executeWithoutResult(s -> insertPosting(insertEntry(), vault, -500)))
        .hasMessageContaining("at least 2");
  }

  @Test
  void entryWithNoPostingsIsRejected() {
    assertThatThrownBy(() -> transactions.executeWithoutResult(s -> insertEntry()))
        .hasMessageContaining("0 posting(s)");
  }

  @Test
  void balancedEntryCommits() {
    transactions.executeWithoutResult(
        s -> {
          UUID entry = insertEntry();
          insertPosting(entry, vault, -500);
          insertPosting(entry, mule, 500);
          // Raw SQL bypasses the service, so keep the stored balances honest by hand.
          adjustBalance(vault, -500);
          adjustBalance(mule, 500);
        });
  }

  @Test
  void postingsAndEntriesAreAppendOnly() {
    assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount_cents = 1").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entries SET description = 'x'").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("TRUNCATE postings CASCADE").update())
        .hasMessageContaining("append-only");
  }

  @Test
  void nonEquityBalanceCannotGoNegative() {
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE account_balances SET balance_cents = -1 WHERE account_id = :id")
                    .param("id", mule)
                    .update())
        .hasMessageContaining("account_balances_non_negative");
  }

  @Test
  void balanceRowCannotClaimADifferentKindThanItsAccount() {
    // The CHECK only exempts equity, so a balance row must not be able to pretend.
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE account_balances SET kind = 'equity' WHERE account_id = :id")
                    .param("id", mule)
                    .update())
        .hasMessageContaining("account_balances_account_id_kind_fkey");
  }

  @Test
  void attemptsArePartitionedAheadOfTime() {
    long partitions =
        jdbc.sql(
                """
                SELECT count(*) FROM pg_inherits i
                  JOIN pg_class parent ON parent.oid = i.inhparent
                 WHERE parent.relname = 'attempts'
                """)
            .query(Long.class)
            .single();
    assertThat(partitions).isGreaterThanOrEqualTo(3);
  }

  private UUID insertEntry() {
    return jdbc.sql("INSERT INTO journal_entries (description) VALUES ('raw sql') RETURNING id")
        .query(UUID.class)
        .single();
  }

  private void adjustBalance(UUID account, long cents) {
    jdbc.sql("UPDATE account_balances SET balance_cents = balance_cents + :c WHERE account_id = :a")
        .param("c", cents)
        .param("a", account)
        .update();
  }

  private void insertPosting(UUID entry, UUID account, long cents) {
    jdbc.sql("INSERT INTO postings (entry_id, account_id, amount_cents) VALUES (:e, :a, :c)")
        .param("e", entry)
        .param("a", account)
        .param("c", cents)
        .update();
  }
}
