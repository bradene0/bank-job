package dev.bradenevans.bankjob.gameapi.branch;

import dev.bradenevans.bankjob.gameapi.content.DailyPicks.SecurityTopic;
import dev.bradenevans.bankjob.gameapi.content.GameContent;
import dev.bradenevans.bankjob.gameapi.ledger.AccountKind;
import dev.bradenevans.bankjob.gameapi.ledger.AccountSnapshot;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerService;
import dev.bradenevans.bankjob.gameapi.ledger.Leg;
import dev.bradenevans.bankjob.gameapi.ledger.PostingRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a session's private copy of the branch: customers, their accounts, the vault, the player's
 * mule account, and one opening journal entry that funds everything from the session's equity
 * account (ADR-0009). Security questions follow the session's daily seed.
 */
@Service
public class BranchProvisioner {

  private final CustomerRepository customers;
  private final LedgerService ledger;
  private final GameContent content;
  private final PasswordEncoder passwordEncoder;
  // bcrypt is deliberately slow and every session of a day shares the same answers, so hash
  // each (customer, topic) pair once per process.
  private final Map<String, String> answerHashes = new ConcurrentHashMap<>();

  BranchProvisioner(
      CustomerRepository customers,
      LedgerService ledger,
      GameContent content,
      PasswordEncoder passwordEncoder) {
    this.customers = customers;
    this.ledger = ledger;
    this.content = content;
    this.passwordEncoder = passwordEncoder;
    for (SecurityTopic topic : content.securityTopics()) {
      for (BranchSeed.SeedCustomer seed : BranchSeed.CUSTOMERS) {
        if (!seed.answers().containsKey(topic.id())) {
          throw new IllegalStateException(
              seed.displayName() + " has no answer for security topic " + topic.id());
        }
      }
    }
  }

  /** Answers are compared case- and whitespace-insensitively. */
  static String normalizeAnswer(String answer) {
    return answer.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void provision(UUID sessionId, String dailySeed) {
    SecurityTopic topic = content.picks(dailySeed).securityTopic();
    AccountSnapshot equity =
        ledger.openAccount(sessionId, null, BranchSeed.EQUITY_NUMBER, AccountKind.EQUITY);
    List<Leg> openingLegs = new ArrayList<>();

    Customer custodian = null;
    for (BranchSeed.SeedCustomer seed : BranchSeed.CUSTOMERS) {
      String answer = seed.answers().get(topic.id());
      Customer customer =
          customers.save(
              new Customer(
                  sessionId,
                  seed,
                  topic.question(),
                  answerHash(seed, topic, answer),
                  BranchSeed.hintFor(answer)));
      if (custodian == null) {
        custodian = customer;
      }
      AccountSnapshot checking =
          ledger.openAccount(sessionId, customer.id(), seed.checkingNumber(), AccountKind.CHECKING);
      openingLegs.add(new Leg(checking.id(), seed.checkingOpeningCents()));
    }

    AccountSnapshot vault =
        ledger.openAccount(sessionId, custodian.id(), BranchSeed.VAULT_NUMBER, AccountKind.VAULT);
    openingLegs.add(new Leg(vault.id(), BranchSeed.VAULT_OPENING_CENTS));
    ledger.openAccount(sessionId, null, BranchSeed.MULE_NUMBER, AccountKind.MULE);

    long total = openingLegs.stream().mapToLong(Leg::amountCents).sum();
    openingLegs.add(new Leg(equity.id(), -total));
    ledger.post(new PostingRequest("Opening balances", null, openingLegs));
  }

  private String answerHash(BranchSeed.SeedCustomer seed, SecurityTopic topic, String answer) {
    return answerHashes.computeIfAbsent(
        seed.displayName() + "|" + topic.id(),
        key -> passwordEncoder.encode(normalizeAnswer(answer)));
  }

  /** Customer display names by id, for labelling accounts in API responses. */
  @Transactional(readOnly = true)
  public Map<UUID, String> customerNames(UUID sessionId) {
    return customers.findBySessionId(sessionId).stream()
        .collect(Collectors.toUnmodifiableMap(Customer::id, Customer::displayName));
  }
}
