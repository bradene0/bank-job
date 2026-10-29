package dev.bradenevans.bankjob.gameapi.accounts;

public class AccountNotFoundException extends RuntimeException {

  AccountNotFoundException(String number) {
    super("No account " + number + " in this session");
  }
}
