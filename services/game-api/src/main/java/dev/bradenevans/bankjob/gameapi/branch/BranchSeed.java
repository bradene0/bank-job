package dev.bradenevans.bankjob.gameapi.branch;

import java.util.List;

/**
 * The fictional Brassvale branch every session starts with. All names, numbers and answers are
 * invented; 555-01xx phone numbers are reserved for fiction.
 */
final class BranchSeed {

  record SeedCustomer(
      String displayName,
      String securityQuestion,
      String securityAnswer,
      String phone,
      String checkingNumber,
      long checkingOpeningCents) {}

  static final String VAULT_NUMBER = "BV-VAULT-01";
  static final String MULE_NUMBER = "BV-MULE-01";
  static final String EQUITY_NUMBER = "BV-EQUITY-01";
  static final long VAULT_OPENING_CENTS = 25_000_000; // $250,000

  /** The first customer is the vault custodian. */
  static final List<SeedCustomer> CUSTOMERS =
      List.of(
          new SeedCustomer(
              "Margaret Holloway",
              "What was the name of your first pet?",
              "Biscuit",
              "+1-555-0101",
              "BV-CHK-1001",
              1_240_000),
          new SeedCustomer(
              "Desmond Achebe",
              "What street did you grow up on?",
              "Larkspur Lane",
              "+1-555-0102",
              "BV-CHK-1002",
              315_000),
          new SeedCustomer(
              "Lupe Ferraro",
              "What is your favorite bread?",
              "Focaccia",
              "+1-555-0103",
              "BV-CHK-1003",
              872_000),
          new SeedCustomer(
              "Theo Lindqvist",
              "What was the name of your first pet?",
              "Pickles",
              "+1-555-0104",
              "BV-CHK-1004",
              198_000),
          new SeedCustomer(
              "Opal Nakamura",
              "What street did you grow up on?",
              "Wren Street",
              "+1-555-0105",
              "BV-CHK-1005",
              2_750_000));

  private BranchSeed() {}
}
