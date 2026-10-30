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

  /** The ledger is the core: it knows nothing about sessions, HTTP or game content. */
  @ArchTest
  static final ArchRule ledgerDependsOnNoOtherModule =
      noClasses()
          .that()
          .resideInAPackage("..gameapi.ledger..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..gameapi.accounts..",
              "..gameapi.attempt..",
              "..gameapi.branch..",
              "..gameapi.content..",
              "..gameapi.maintenance..",
              "..gameapi.mcp..",
              "..gameapi.play..",
              "..gameapi.policy..",
              "..gameapi.security..",
              "..gameapi.session..",
              "..gameapi.tools..",
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
