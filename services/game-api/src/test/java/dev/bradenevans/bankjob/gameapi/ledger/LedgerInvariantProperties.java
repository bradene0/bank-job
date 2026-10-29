package dev.bradenevans.bankjob.gameapi.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bradenevans.bankjob.gameapi.GameApiApplication;
import dev.bradenevans.bankjob.gameapi.ledger.TransferResult.Status;
import dev.bradenevans.bankjob.gameapi.support.TestBranches;
import dev.bradenevans.bankjob.gameapi.support.TestcontainersConfiguration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Property-based test of the ledger invariants: for any generated sequence of transfers (including
 * impossible ones), the real service on a real database behaves exactly like a trivial in-memory
 * model, and the double-entry invariants hold. jqwik shrinks a failure to the shortest sequence
 * that still breaks.
 *
 * <p>jqwik is its own JUnit Platform engine, so this boots the application directly instead of
 * using Spring's JUnit Jupiter extension.
 */
class LedgerInvariantProperties {

  record Transfer(int from, int to, long cents) {}

  static PostgreSQLContainer postgres;
  static ConfigurableApplicationContext context;
  static LedgerService ledger;
  static LedgerReconciler reconciler;
  static TestBranches branches;

  @BeforeContainer
  static void startApplication() {
    postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES);
    postgres.start();
    context =
        new SpringApplicationBuilder(GameApiApplication.class)
            .profiles("test")
            // Command-line arguments outrank application.yaml (builder defaults do not).
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword());
    ledger = context.getBean(LedgerService.class);
    reconciler = context.getBean(LedgerReconciler.class);
    branches = context.getBean(TestBranches.class);
  }

  @AfterContainer
  static void stopApplication() {
    if (context != null) {
      context.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Property(tries = 100)
  void anySequenceOfTransfersPreservesTheLedgerInvariants(
      @ForAll("transferScripts") List<Transfer> script) {
    UUID session = branches.newSession();
    List<AccountSnapshot> accounts =
        branches.accounts(session).values().stream()
            .sorted(Comparator.comparing(AccountSnapshot::number))
            .toList();
    Map<UUID, Long> model = new HashMap<>();
    accounts.forEach(a -> model.put(a.id(), a.balanceCents()));

    for (Transfer step : script) {
      AccountSnapshot from = accounts.get(step.from());
      AccountSnapshot to = accounts.get(step.to());
      boolean affordable =
          from.kind() == AccountKind.EQUITY || model.get(from.id()) >= step.cents();

      TransferResult result =
          ledger.transfer(
              new TransferCommand(
                  session, from.id(), to.id(), step.cents(), "property", null, null));

      assertThat(result.status())
          .isEqualTo(affordable ? Status.POSTED : Status.REJECTED_INSUFFICIENT_FUNDS);
      if (affordable) {
        model.merge(from.id(), -step.cents(), Long::sum);
        model.merge(to.id(), step.cents(), Long::sum);
      }
    }

    for (AccountSnapshot after : branches.accounts(session).values()) {
      assertThat(after.balanceCents()).as(after.number()).isEqualTo(model.get(after.id()));
      if (after.kind() != AccountKind.EQUITY) {
        assertThat(after.balanceCents()).as(after.number()).isNotNegative();
      }
    }
    assertThat(reconciler.balanceMismatches(session)).isEmpty();
    assertThat(reconciler.sessionNetCents(session)).isZero();
    assertThat(reconciler.invalidJournalEntries()).isEmpty();
  }

  @Provide
  Arbitrary<List<Transfer>> transferScripts() {
    Arbitrary<Integer> account = Arbitraries.integers().between(0, 7); // 8 accounts per branch
    // Mostly affordable amounts, some large enough to overdraw most accounts.
    Arbitrary<Long> cents =
        Arbitraries.frequencyOf(
            Tuple.of(6, Arbitraries.longs().between(1, 50_000)),
            Tuple.of(3, Arbitraries.longs().between(50_001, 3_000_000)),
            Tuple.of(1, Arbitraries.longs().between(3_000_001, 40_000_000)));
    return Combinators.combine(account, account, cents)
        .filter((from, to, amount) -> !from.equals(to))
        .as(Transfer::new)
        .list()
        .ofMaxSize(30);
  }
}
