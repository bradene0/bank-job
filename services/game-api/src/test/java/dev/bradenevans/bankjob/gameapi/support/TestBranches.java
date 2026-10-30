package dev.bradenevans.bankjob.gameapi.support;

import dev.bradenevans.bankjob.gameapi.attempt.AttemptRecord;
import dev.bradenevans.bankjob.gameapi.attempt.AttemptStore;
import dev.bradenevans.bankjob.gameapi.branch.BranchProvisioner;
import dev.bradenevans.bankjob.gameapi.ledger.AccountSnapshot;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerService;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates a fresh, fully provisioned session for a test. Each test gets its own branch, so tests
 * never share balances and need no cleanup (the ledger is append-only anyway).
 */
@Component
public class TestBranches {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final BranchProvisioner provisioner;
  private final LedgerService ledger;
  private final AttemptStore attempts;

  public TestBranches(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      BranchProvisioner provisioner,
      LedgerService ledger,
      AttemptStore attempts) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.provisioner = provisioner;
    this.ledger = ledger;
    this.attempts = attempts;
  }

  /** The seed every test branch uses, so security answers are predictable. */
  public static final String DAILY_SEED = "test-seed";

  public UUID newSession() {
    return transactions.execute(
        status -> {
          UUID sessionId =
              jdbc.sql(
                      """
                      INSERT INTO sessions (expires_at, ip_hash, daily_seed, nerve_remaining)
                      VALUES (now() + interval '2 hours', 'test', :seed,
                              '{"1": 8, "2": 7, "3": 6, "4": 5, "5": 4}'::jsonb)
                      RETURNING id
                      """)
                  .param("seed", DAILY_SEED)
                  .query(UUID.class)
                  .single();
          provisioner.provision(sessionId, DAILY_SEED);
          return sessionId;
        });
  }

  /** An in-flight attempt, as POST /v1/attempts would create it before calling the agent. */
  public AttemptRecord newAttempt(UUID sessionId, int level, String comboId) {
    String[] ids = comboId.split("\\.");
    ObjectNode tiles = JsonNodeFactory.instance.objectNode();
    tiles
        .put("persona", ids[0])
        .put("pretext", ids[1])
        .put("technique", ids[2])
        .put("payload", ids[3]);
    return attempts.create(sessionId, level, comboId, tiles, DAILY_SEED, null);
  }

  /** The session's accounts keyed by number, e.g. "BV-MULE-01". */
  public Map<String, AccountSnapshot> accounts(UUID sessionId) {
    return ledger.accountsForSession(sessionId).stream()
        .collect(Collectors.toMap(AccountSnapshot::number, Function.identity()));
  }

  public long journalEntryCount() {
    return jdbc.sql("SELECT count(*) FROM journal_entries").query(Long.class).single();
  }
}
