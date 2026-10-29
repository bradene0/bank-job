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
              "..gameapi.branch..",
              "..gameapi.content..",
              "..gameapi.maintenance..",
              "..gameapi.security..",
              "..gameapi.session..",
              "..gameapi.web..");

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
