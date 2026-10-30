package dev.bradenevans.bankjob.gameapi.ledger;

import dev.bradenevans.bankjob.gameapi.events.EventType;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerExceptions.CrossSessionTransferException;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerExceptions.IdempotencyConflictException;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerExceptions.InsufficientFundsException;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerExceptions.UnknownAccountException;
import dev.bradenevans.bankjob.gameapi.outbox.Outbox;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The only way to write the ledger. */
@Service
@RegisterReflectionForBinding(TransferResult.class) // stored as JSON for idempotent replays
public class LedgerService {

  private static final String IDEMPOTENCY_KEY_CONSTRAINT = "idempotency_keys_pkey";

  private final AccountRepository accounts;
  private final AccountBalanceRepository balances;
  private final IdempotencyKeyRepository idempotencyKeys;
  private final EntityManager entityManager;
  private final TransactionTemplate transactions;
  private final JsonMapper json;
  private final Outbox outbox;

  LedgerService(
      AccountRepository accounts,
      AccountBalanceRepository balances,
      IdempotencyKeyRepository idempotencyKeys,
      EntityManager entityManager,
      TransactionTemplate transactions,
      JsonMapper json,
      Outbox outbox) {
    this.accounts = accounts;
    this.balances = balances;
    this.idempotencyKeys = idempotencyKeys;
    this.entityManager = entityManager;
    this.transactions = transactions;
    this.json = json;
    this.outbox = outbox;
  }

  /** Opens an account with a zero balance. Money only arrives through journal entries. */
  @Transactional
  public AccountSnapshot openAccount(
      UUID sessionId, UUID customerId, String number, AccountKind kind) {
    Account account = new Account(sessionId, customerId, number, kind);
    entityManager.persist(account);
    AccountBalance balance = new AccountBalance(account);
    entityManager.persist(balance);
    return snapshot(account, balance);
  }

  /**
   * Posts a balanced journal entry and updates the affected balances, joining the caller's
   * transaction if there is one. Used for system entries such as opening balances.
   *
   * @throws InsufficientFundsException if a non-equity account would go negative
   */
  @Transactional
  public UUID post(PostingRequest request) {
    Map<UUID, AccountBalance> byAccount = loadBalances(request.legs());
    for (Leg leg : request.legs()) {
      if (!byAccount.get(leg.accountId()).canApply(leg.amountCents())) {
        throw new InsufficientFundsException(leg.accountId());
      }
    }
    JournalEntry entry = new JournalEntry(request.description(), request.attempt());
    entityManager.persist(entry);
    for (Leg leg : request.legs()) {
      entityManager.persist(new Posting(entry.id(), leg.accountId(), leg.amountCents()));
      byAccount.get(leg.accountId()).apply(leg.amountCents());
    }
    return entry.id();
  }

  /**
   * Moves money between two accounts of the same session in its own transaction. A posted transfer
   * also writes its TransferPosted event to the outbox, in the same transaction.
   *
   * <p>Concurrency: if another transaction updated either balance first, the version check fails at
   * commit and the whole transaction is retried with fresh balances. A concurrent duplicate
   * idempotency key surfaces as a unique-key violation and is retried too; the retry finds the
   * committed key and replays its result.
   *
   * <p>Backoff: 5 ms doubling to at most 250 ms, randomized (Spring scales the jitter with the
   * interval), for up to 40 retries. The budget is sized for the worst case in LedgerConcurrencyIT
   * (16 threads on 4 accounts, on a 2-vCPU CI runner); in the game each session has its own branch,
   * so real contention on one balance is rare.
   */
  @Retryable(
      includes = {ConcurrencyFailureException.class, DuplicateKeyException.class},
      maxRetries = 40,
      delay = 5,
      jitter = 5,
      multiplier = 2,
      maxDelay = 250)
  public TransferResult transfer(TransferCommand command) {
    Assert.state(
        !TransactionSynchronizationManager.isActualTransactionActive(),
        "transfer() needs its own transaction so it can be retried; call it outside one");
    try {
      return transactions.execute(status -> transferOnce(command));
    } catch (DataIntegrityViolationException e) {
      if (isIdempotencyKeyCollision(e)) {
        // Another transaction committed this key first; retrying will replay its result.
        throw new DuplicateKeyException("Concurrent use of idempotency key", e);
      }
      throw e;
    }
  }

  private static boolean isIdempotencyKeyCollision(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException violation) {
        return IDEMPOTENCY_KEY_CONSTRAINT.equals(violation.getConstraintName());
      }
    }
    return false;
  }

  private TransferResult transferOnce(TransferCommand command) {
    String key = command.idempotencyKey();
    String requestHash = command.requestHash();
    if (key != null) {
      Optional<IdempotencyRecord> existing = idempotencyKeys.findById(key);
      if (existing.isPresent()) {
        if (!existing.get().requestHash().equals(requestHash)) {
          throw new IdempotencyConflictException(key);
        }
        return json.readValue(existing.get().responseJson(), TransferResult.class).asReplay();
      }
    }

    Account from = requireAccount(command.fromAccountId());
    Account to = requireAccount(command.toAccountId());
    if (!from.sessionId().equals(command.sessionId())
        || !to.sessionId().equals(command.sessionId())) {
      throw new CrossSessionTransferException();
    }
    AccountBalance fromBalance = requireBalance(from.id());
    AccountBalance toBalance = requireBalance(to.id());

    TransferResult result;
    if (!fromBalance.canApply(-command.amountCents())) {
      result =
          TransferResult.insufficientFunds(fromBalance.balanceCents(), toBalance.balanceCents());
    } else {
      UUID entryId =
          post(
              new PostingRequest(
                  command.description(),
                  command.attempt(),
                  List.of(
                      new Leg(from.id(), -command.amountCents()),
                      new Leg(to.id(), command.amountCents()))));
      result = TransferResult.posted(entryId, fromBalance.balanceCents(), toBalance.balanceCents());
      transferPosted(command, entryId, from, to);
    }

    if (key != null) {
      // persist, never merge: a concurrent duplicate must fail on the primary key.
      entityManager.persist(
          new IdempotencyRecord(key, requestHash, json.writeValueAsString(result)));
    }
    return result;
  }

  /** Keyed by the destination account: its credits stay in order for the anomaly scorer. */
  private void transferPosted(TransferCommand command, UUID entryId, Account from, Account to) {
    ObjectNode event = json.createObjectNode();
    event.put("entry_id", entryId.toString());
    event.put("session_id", command.sessionId().toString());
    event.put("attempt_id", command.attempt() == null ? null : command.attempt().id().toString());
    event.put("from_account_id", from.id().toString());
    event.put("from_account", from.number());
    event.put("to_account_id", to.id().toString());
    event.put("to_account", to.number());
    event.put("amount_cents", command.amountCents());
    String account = to.id().toString();
    outbox.append(EventType.TRANSFER_POSTED, "account", account, account, event);
  }

  @Transactional(readOnly = true)
  public List<AccountSnapshot> accountsForSession(UUID sessionId) {
    List<Account> sessionAccounts = accounts.findBySessionIdOrderByNumber(sessionId);
    Map<UUID, AccountBalance> byAccount =
        balances.findAllById(sessionAccounts.stream().map(Account::id).toList()).stream()
            .collect(Collectors.toMap(AccountBalance::accountId, Function.identity()));
    return sessionAccounts.stream().map(a -> snapshot(a, byAccount.get(a.id()))).toList();
  }

  @Transactional(readOnly = true)
  public Optional<AccountSnapshot> account(UUID sessionId, String number) {
    return accounts
        .findBySessionIdAndNumber(sessionId, number)
        .map(a -> snapshot(a, requireBalance(a.id())));
  }

  // findById rather than getReferenceById: no lazy proxies, which native images cannot
  // generate at runtime.
  private AccountBalance requireBalance(UUID accountId) {
    return balances.findById(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
  }

  private Account requireAccount(UUID accountId) {
    return accounts.findById(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
  }

  private Map<UUID, AccountBalance> loadBalances(List<Leg> legs) {
    List<UUID> ids = legs.stream().map(Leg::accountId).toList();
    Map<UUID, AccountBalance> byAccount =
        balances.findAllById(ids).stream()
            .collect(Collectors.toMap(AccountBalance::accountId, Function.identity()));
    for (UUID id : ids) {
      if (!byAccount.containsKey(id)) {
        throw new UnknownAccountException(id);
      }
    }
    return byAccount;
  }

  private static AccountSnapshot snapshot(Account account, AccountBalance balance) {
    return new AccountSnapshot(
        account.id(),
        account.sessionId(),
        account.customerId(),
        account.number(),
        account.kind(),
        account.currency(),
        balance.balanceCents());
  }
}
