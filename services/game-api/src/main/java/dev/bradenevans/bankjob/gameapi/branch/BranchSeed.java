package dev.bradenevans.bankjob.gameapi.branch;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fictional Brassvale branch every session starts with. All names, numbers and answers are
 * invented; 555-01xx phone numbers are reserved for fiction.
 */
final class BranchSeed {

  /** Answers by security topic id (content/daily.yaml). */
  record SeedCustomer(
      String displayName,
      Map<String, String> answers,
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
              answers("Biscuit", "Juniper Row", "Sourdough"),
              "+1-555-0101",
              "BV-CHK-1001",
              1_240_000),
          new SeedCustomer(
              "Desmond Achebe",
              answers("Captain", "Larkspur Lane", "Rye"),
              "+1-555-0102",
              "BV-CHK-1002",
              315_000),
          new SeedCustomer(
              "Lupe Ferraro",
              answers("Noodle", "Harbor Street", "Focaccia"),
              "+1-555-0103",
              "BV-CHK-1003",
              872_000),
          new SeedCustomer(
              "Theo Lindqvist",
              answers("Pickles", "Alder Way", "Pumpernickel"),
              "+1-555-0104",
              "BV-CHK-1004",
              198_000),
          new SeedCustomer(
              "Opal Nakamura",
              answers("Mochi", "Wren Street", "Brioche"),
              "+1-555-0105",
              "BV-CHK-1005",
              2_750_000));

  private static Map<String, String> answers(String pet, String street, String bread) {
    return Map.of("first_pet", pet, "childhood_street", street, "favorite_bread", bread);
  }

  /**
   * The customer's "memory aid": the answer spelled out letter by letter ("B-I-S-C-U-I-T"). It is
   * what lookup_account leaks on purpose (Level 3).
   */
  static String hintFor(String answer) {
    StringBuilder hint = new StringBuilder();
    for (String word : answer.toUpperCase(Locale.ROOT).split(" ")) {
      if (!hint.isEmpty()) {
        hint.append(' ');
      }
      hint.append(String.join("-", word.split("")));
    }
    return hint.toString();
  }

  private BranchSeed() {}
}
