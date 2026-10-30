package dev.bradenevans.bankjob.gameapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/** Module boundaries, checked on every build. */
@AnalyzeClasses(
    packages = "dev.bradenevans.bankjob.gameapi",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /**
   * The ledger is the core: it knows nothing about sessions, HTTP or game content. It may append to
   * the outbox (TransferPosted commits with the postings).
   */
  @ArchTest
  static final ArchRule ledgerDependsOnNoOtherModule =
      noClasses()
          .that()
          .resideInAPackage("..gameapi.ledger..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..gameapi.accounts..",
              "..gameapi.anomaly..",
              "..gameapi.attempt..",
              "..gameapi.audit..",
              "..gameapi.branch..",
              "..gameapi.content..",
              "..gameapi.leaderboard..",
              "..gameapi.maintenance..",
              "..gameapi.mcp..",
              "..gameapi.play..",
              "..gameapi.policy..",
              "..gameapi.security..",
              "..gameapi.session..",
              "..gameapi.tools..",
              "..gameapi.traces..",
              "..gameapi.web..");

  /**
   * The policy engine decides from facts and level controls only. It never sees transport, the
   * agent client or the model's text, so nothing a model says can reach an authorization decision.
   */
  @ArchTest
  static final ArchRule policyIsIndependentOfTransportAndTheAgent =
      noClasses()
          .that()
          .resideInAPackage("..gameapi.policy..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..gameapi.mcp..", "..gameapi.play..", "..gameapi.tools..");

  /** Money moves only through the bank tools (and branch provisioning). */
  @ArchTest
  static final ArchRule onlyToolsAndProvisioningWriteTheLedger =
      noClasses()
          .that()
          .resideOutsideOfPackages("..gameapi.ledger..", "..gameapi.tools..", "..gameapi.branch..")
          .should()
          .callMethod(
              dev.bradenevans.bankjob.gameapi.ledger.LedgerService.class,
              "transfer",
              dev.bradenevans.bankjob.gameapi.ledger.TransferCommand.class)
          .orShould()
          .callMethod(
              dev.bradenevans.bankjob.gameapi.ledger.LedgerService.class,
              "post",
              dev.bradenevans.bankjob.gameapi.ledger.PostingRequest.class);

  /**
   * Events reach Kafka only through the transactional outbox (ADR-0002): no other code may send to
   * the broker, so no change can publish an event its transaction did not commit. (The DLQ
   * recoverer in events.KafkaConfig re-sends records consumers gave up on.)
   */
  @ArchTest
  static final ArchRule onlyTheOutboxProducesEvents =
      noClasses()
          .that()
          .resideOutsideOfPackages("..gameapi.outbox..", "..gameapi.events..")
          .should()
          .dependOnClassesThat()
          .areAssignableTo(org.springframework.kafka.core.KafkaOperations.class)
          .orShould()
          .dependOnClassesThat()
          .areAssignableTo(org.apache.kafka.clients.producer.Producer.class);

  /** Consumers keep projections; they never touch the ledger, the tools or the play loop. */
  @ArchTest
  static final ArchRule consumersOnlyKeepProjections =
      noClasses()
          .that()
          .resideInAnyPackage("..gameapi.anomaly..", "..gameapi.leaderboard..", "..gameapi.audit..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..gameapi.ledger..", "..gameapi.tools..", "..gameapi.play..", "..gameapi.mcp..");

  @ArchTest
  static final ArchRule modulesHaveNoCycles =
      slices().matching("dev.bradenevans.bankjob.gameapi.(*)..").should().beFreeOfCycles();

  /** Controllers go through services, never straight to a repository. */
  @ArchTest
  static final ArchRule controllersDoNotUseRepositories =
      noClasses()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .dependOnClassesThat()
          .areAssignableTo(Repository.class);
}
