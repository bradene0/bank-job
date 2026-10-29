package dev.bradenevans.bankjob.gameapi.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.ledger.TransferResult.Status;
import dev.bradenevans.bankjob.gameapi.support.IntegrationTest;
import dev.bradenevans.bankjob.gameapi.support.TestBranches;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Many threads hammer the same few balances at once. Optimistic locking plus retries must give the
 * same guarantees as running the transfers one by one: no lost updates, no overdrafts, every entry
 * balanced, balances equal to their postings.
 */
@IntegrationTest
class LedgerConcurrencyIT {

  static final int THREADS = 16;
  static final int TRANSFERS_PER_THREAD = 25;
  static final List<String> HOT_ACCOUNTS =
      List.of("BV-CHK-1002", "BV-CHK-1004", "BV-CHK-1003", "BV-MULE-01");

  @Autowired LedgerService ledger;
  @Autowired LedgerReconciler reconciler;
  @Autowired TestBranches branches;

  @Test
  void parallelTransfersBetweenHotAccountsLoseNothing() throws Exception {
    UUID session = branches.newSession();
    Map<String, AccountSnapshot> before = branches.accounts(session);
    long hotTotalBefore = total(before);
    long entriesBefore = branches.journalEntryCount();

    List<Callable<TransferResult>> work = new ArrayList<>();
    for (int i = 0; i < THREADS * TRANSFERS_PER_THREAD; i++) {
      work.add(
          () -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int from = random.nextInt(HOT_ACCOUNTS.size());
            int to = (from + 1 + random.nextInt(HOT_ACCOUNTS.size() - 1)) % HOT_ACCOUNTS.size();
            // Up to $2,500 per transfer against balances of $0-$8,720: plenty of rejections.
            long cents = 1 + random.nextLong(250_000);
            return ledger.transfer(
                new TransferCommand(
                    session,
                    before.get(HOT_ACCOUNTS.get(from)).id(),
                    before.get(HOT_ACCOUNTS.get(to)).id(),
                    cents,
                    "concurrency test",
                    null,
                    null));
          });
    }
    List<TransferResult> results = runConcurrently(work);

    long posted = results.stream().filter(r -> r.status() == Status.POSTED).count();
    long rejected =
        results.stream().filter(r -> r.status() == Status.REJECTED_INSUFFICIENT_FUNDS).count();
    Map<String, AccountSnapshot> after = branches.accounts(session);

    assertThat(posted + rejected).isEqualTo(work.size());
    assertThat(posted).isPositive();
    assertThat(branches.journalEntryCount() - entriesBefore).isEqualTo(posted);
    assertThat(total(after)).isEqualTo(hotTotalBefore); // money only moved between them
    assertThat(HOT_ACCOUNTS)
        .allSatisfy(n -> assertThat(after.get(n).balanceCents()).isNotNegative());
    assertThat(reconciler.balanceMismatches(session)).isEmpty();
    assertThat(reconciler.invalidJournalEntries()).isEmpty();
    assertThat(reconciler.sessionNetCents(session)).isZero();
  }

  @Test
  void concurrentRetriesWithOneIdempotencyKeyMoveMoneyExactlyOnce() throws Exception {
    UUID session = branches.newSession();
    Map<String, AccountSnapshot> accounts = branches.accounts(session);
    TransferCommand command =
        new TransferCommand(
            session,
            accounts.get("BV-VAULT-01").id(),
            accounts.get("BV-MULE-01").id(),
            500_000,
            "same key",
            null,
            "dup-" + UUID.randomUUID());

    List<Callable<TransferResult>> work = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      work.add(() -> ledger.transfer(command));
    }
    List<TransferResult> results = runConcurrently(work);

    assertThat(results)
        .extracting(TransferResult::journalEntryId)
        .containsOnly(results.getFirst().journalEntryId());
    assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
    assertThat(branches.accounts(session).get("BV-MULE-01").balanceCents()).isEqualTo(500_000);
  }

  private static long total(Map<String, AccountSnapshot> accounts) {
    return HOT_ACCOUNTS.stream().mapToLong(n -> accounts.get(n).balanceCents()).sum();
  }

  /** Releases every task at the same instant to maximize contention. */
  private static <T> List<T> runConcurrently(List<Callable<T>> work) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : work) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return task.call();
                }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get()); // rethrows anything that escaped the retries
      }
      return results;
    }
  }
}
