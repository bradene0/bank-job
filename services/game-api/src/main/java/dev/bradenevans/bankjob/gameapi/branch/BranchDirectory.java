package dev.bradenevans.bankjob.gameapi.branch;

import dev.bradenevans.bankjob.gameapi.ledger.AccountKind;
import dev.bradenevans.bankjob.gameapi.ledger.AccountSnapshot;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerService;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who holds which account in a session's branch: what the bank tools read and change. */
@Service
public class BranchDirectory {

  public static final String VAULT_NUMBER = BranchSeed.VAULT_NUMBER;
  public static final String MULE_NUMBER = BranchSeed.MULE_NUMBER;
  private static final int MAX_MATCHES = 5;

  /** An account and its owner. Owner fields are null for the player's mule account. */
  public record AccountHolder(
      UUID accountId,
      String accountNumber,
      AccountKind kind,
      long balanceCents,
      UUID customerId,
      String ownerName,
      String phone,
      String securityQuestion,
      String answerHint) {}

  private final LedgerService ledger;
  private final CustomerRepository customers;
  private final PasswordEncoder passwordEncoder;

  BranchDirectory(
      LedgerService ledger, CustomerRepository customers, PasswordEncoder passwordEncoder) {
    this.ledger = ledger;
    this.customers = customers;
    this.passwordEncoder = passwordEncoder;
  }

  /** Accounts whose number or owner name contains {@code hint} (at least 3 characters). */
  @Transactional(readOnly = true)
  public List<AccountHolder> lookup(UUID sessionId, String hint) {
    String needle = hint.strip().toLowerCase(Locale.ROOT);
    if (needle.length() < 3) {
      return List.of();
    }
    return holders(sessionId).stream()
        .filter(
            h ->
                h.accountNumber().toLowerCase(Locale.ROOT).contains(needle)
                    || (h.ownerName() != null
                        && h.ownerName().toLowerCase(Locale.ROOT).contains(needle)))
        .limit(MAX_MATCHES)
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<AccountHolder> holder(UUID sessionId, String accountNumber) {
    String number = accountNumber.strip().toUpperCase(Locale.ROOT);
    return holders(sessionId).stream().filter(h -> h.accountNumber().equals(number)).findFirst();
  }

  @Transactional(readOnly = true)
  public boolean answerMatches(UUID customerId, String answer) {
    return customers
        .findById(customerId)
        .map(
            c ->
                passwordEncoder.matches(
                    BranchProvisioner.normalizeAnswer(answer), c.securityAnswerHash()))
        .orElse(false);
  }

  @Transactional
  public void changePhone(UUID customerId, String phone) {
    customers.findById(customerId).orElseThrow().changePhone(phone);
  }

  /** Every account except the equity account, which is bookkeeping and not part of the game. */
  private List<AccountHolder> holders(UUID sessionId) {
    Map<UUID, Customer> byId =
        customers.findBySessionId(sessionId).stream()
            .collect(Collectors.toMap(Customer::id, Function.identity()));
    return ledger.accountsForSession(sessionId).stream()
        .filter(a -> a.kind() != AccountKind.EQUITY)
        .sorted(Comparator.comparing(AccountSnapshot::number))
        .map(a -> holder(a, a.customerId() == null ? null : byId.get(a.customerId())))
        .toList();
  }

  private static AccountHolder holder(AccountSnapshot account, Customer owner) {
    return new AccountHolder(
        account.id(),
        account.number(),
        account.kind(),
        account.balanceCents(),
        owner == null ? null : owner.id(),
        owner == null ? null : owner.displayName(),
        owner == null ? null : owner.phone(),
        owner == null ? null : owner.securityQuestion(),
        owner == null ? null : owner.securityAnswerHint());
  }
}
