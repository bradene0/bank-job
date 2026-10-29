package dev.bradenevans.bankjob.gameapi.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bradenevans.bankjob.gameapi.ledger.LedgerExceptions.CrossSessionTransferException;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerExceptions.IdempotencyConflictException;
import dev.bradenevans.bankjob.gameapi.ledger.TransferResult.Status;
import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import dev.bradenevans.bankjob.gameapi.support.TestBranches;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class LedgerServiceIT {

  @Autowired LedgerService ledger;
  @Autowired LedgerReconciler reconciler;
  @Autowired TestBranches branches;
  @Autowired TransactionTemplate transactions;

  UUID session;
  Map<String, AccountSnapshot> accounts;

  @BeforeEach
  void setUp() {
    session = branches.newSession();
    accounts = branches.accounts(session);
  }

  @Test
  void provisionedBranchIsFundedByASingleBalancedOpeningEntry() {
    assertThat(accounts).hasSize(8);
    assertThat(accounts.get("BV-VAULT-01").balanceCents()).isEqualTo(25_000_000);
    assertThat(accounts.get("BV-MULE-01").balanceCents()).isZero();
    long fundedCents =
        accounts.values().stream()
            .filter(a -> a.kind() != AccountKind.EQUITY)
            .mapToLong(AccountSnapshot::balanceCents)
            .sum();
    assertThat(accounts.get("BV-EQUITY-01").balanceCents()).isEqualTo(-fundedCents);
    assertThat(reconciler.sessionNetCents(session)).isZero();
  }

  @Test
  void transferMovesMoneyWithBalancedPostings() {
    TransferResult result = ledger.transfer(transfer("BV-VAULT-01", "BV-MULE-01", 20_000, null));

    assertThat(result.status()).isEqualTo(Status.POSTED);
    assertThat(result.journalEntryId()).isNotNull();
    assertThat(result.fromBalanceCents()).isEqualTo(25_000_000 - 20_000);
    assertThat(result.toBalanceCents()).isEqualTo(20_000);
    assertThat(branches.accounts(session).get("BV-MULE-01").balanceCents()).isEqualTo(20_000);
    assertThat(reconciler.balanceMismatches(session)).isEmpty();
    assertThat(reconciler.invalidJournalEntries()).isEmpty();
    assertThat(reconciler.sessionNetCents(session)).isZero();
  }

  @Test
  void insufficientFundsIsARejectionNotAPosting() {
    long entriesBefore = branches.journalEntryCount();

    TransferResult result =
        ledger.transfer(transfer("BV-CHK-1004", "BV-MULE-01", 198_001, null)); // $1 too much

    assertThat(result.status()).isEqualTo(Status.REJECTED_INSUFFICIENT_FUNDS);
    assertThat(result.journalEntryId()).isNull();
    assertThat(branches.journalEntryCount()).isEqualTo(entriesBefore);
    assertThat(branches.accounts(session).get("BV-CHK-1004").balanceCents()).isEqualTo(198_000);
  }

  @Test
  void retryWithTheSameIdempotencyKeyReplaysInsteadOfMovingMoneyTwice() {
    String key = "attempt-" + UUID.randomUUID();
    TransferResult first = ledger.transfer(transfer("BV-VAULT-01", "BV-MULE-01", 500_000, key));
    TransferResult second = ledger.transfer(transfer("BV-VAULT-01", "BV-MULE-01", 500_000, key));

    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).isTrue();
    assertThat(second.journalEntryId()).isEqualTo(first.journalEntryId());
    assertThat(branches.accounts(session).get("BV-MULE-01").balanceCents()).isEqualTo(500_000);
  }

  @Test
  void reusingAKeyForADifferentRequestIsAConflict() {
    String key = "attempt-" + UUID.randomUUID();
    ledger.transfer(transfer("BV-VAULT-01", "BV-MULE-01", 500_000, key));

    assertThatThrownBy(() -> ledger.transfer(transfer("BV-VAULT-01", "BV-MULE-01", 1, key)))
        .isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  void accountsFromAnotherSessionCannotBeUsed() {
    UUID otherMule = branches.accounts(branches.newSession()).get("BV-MULE-01").id();
    TransferCommand command =
        new TransferCommand(
            session, accounts.get("BV-VAULT-01").id(), otherMule, 100, "cross-session", null, null);

    assertThatThrownBy(() -> ledger.transfer(command))
        .isInstanceOf(CrossSessionTransferException.class);
  }

  @Test
  void transferRefusesToRunInsideACallersTransaction() {
    // Retrying inside someone else's transaction would replay a rolled-back unit of work.
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    s -> ledger.transfer(transfer("BV-VAULT-01", "BV-MULE-01", 1, null))))
        .isInstanceOf(IllegalStateException.class);
  }

  private TransferCommand transfer(String from, String to, long cents, String key) {
    return new TransferCommand(
        session, accounts.get(from).id(), accounts.get(to).id(), cents, "test transfer", null, key);
  }
}
