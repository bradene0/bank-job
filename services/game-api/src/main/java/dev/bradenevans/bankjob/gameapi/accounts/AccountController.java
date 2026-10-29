package dev.bradenevans.bankjob.gameapi.accounts;

import dev.bradenevans.bankjob.gameapi.branch.BranchProvisioner;
import dev.bradenevans.bankjob.gameapi.ledger.AccountKind;
import dev.bradenevans.bankjob.gameapi.ledger.AccountSnapshot;
import dev.bradenevans.bankjob.gameapi.ledger.LedgerService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the caller's own branch. The session comes from the token's subject, so a
 * player can never name another session's accounts.
 */
@RestController
class AccountController {

  record AccountView(
      String number, String kind, String owner, long balanceCents, String currency) {}

  record AccountList(List<AccountView> accounts) {}

  private final LedgerService ledger;
  private final BranchProvisioner branch;

  AccountController(LedgerService ledger, BranchProvisioner branch) {
    this.ledger = ledger;
    this.branch = branch;
  }

  @GetMapping("/v1/accounts")
  AccountList list(@AuthenticationPrincipal Jwt token) {
    UUID sessionId = sessionId(token);
    Map<UUID, String> owners = branch.customerNames(sessionId);
    return new AccountList(
        ledger.accountsForSession(sessionId).stream()
            .filter(AccountController::isPlayerVisible)
            .map(a -> view(a, owners))
            .toList());
  }

  @GetMapping("/v1/accounts/{number}")
  AccountView get(@AuthenticationPrincipal Jwt token, @PathVariable String number) {
    UUID sessionId = sessionId(token);
    AccountSnapshot account =
        ledger
            .account(sessionId, number)
            .filter(AccountController::isPlayerVisible)
            .orElseThrow(() -> new AccountNotFoundException(number));
    return view(account, branch.customerNames(sessionId));
  }

  /** The equity account is bookkeeping plumbing, not part of the game. */
  private static boolean isPlayerVisible(AccountSnapshot account) {
    return account.kind() != AccountKind.EQUITY;
  }

  private static AccountView view(AccountSnapshot a, Map<UUID, String> owners) {
    String owner = a.customerId() == null ? null : owners.get(a.customerId());
    return new AccountView(
        a.number(),
        a.kind().name().toLowerCase(Locale.ROOT),
        owner,
        a.balanceCents(),
        a.currency());
  }

  private static UUID sessionId(Jwt token) {
    return UUID.fromString(token.getSubject());
  }
}
